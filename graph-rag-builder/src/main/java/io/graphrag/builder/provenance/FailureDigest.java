package io.graphrag.builder.provenance;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2 trial 실패 다이제스트(REQ-014). {@link TrialRunner}가 후보 invoke 결과가 {@link Outcome.Kind#FAILURE}로
 * 판정될 때 산출한다.
 *
 * <p><b>가드 역매핑(mappedGuard) 2단 매칭:</b>
 * <ol>
 *   <li>1단(정확) — {@code stackExcerpt}의 각 프레임 {@code at <fqn>.<method>(<File>:<line>)}에서
 *       {@code <File>:<line>}만 추출해 {@link ProvenanceReport.GuardFact#at()}과 정확 대조한다.</li>
 *   <li>2단(폴백) — 1단이 실패하면, 각 가드 피연산자({@link ValueRef#literal()})가 응답 바디/로그 텍스트에
 *       부분일치하는 첫 가드를 채택한다. 현재 {@link ProvenanceReport.GuardFact} 스키마에는 "예외 메시지"
 *       전용 필드가 없어(Task 8 인계 — 극성 미기록과 같은 계열의 한계), 가드 피연산자 리터럴(비교 상수·식별
 *       리터럴)을 "검증 메시지"의 최선 근사로 사용한다 — 완전한 대체재는 아니며, 향후 GuardFact에
 *       메시지 필드가 추가되면 그쪽을 우선해야 한다.</li>
 * </ol>
 * 둘 다 실패하면 {@code mappedGuard=null}이며, 이 경우에도 {@code logExcerpt}(원시 로그 구간)는 그대로 보존된다.
 *
 * <p><b>toolSuggestion(경계 만족 패치):</b> mappedGuard가 NUMERIC 비교(op ∈ {@code {">", ">=", "<", "<="}})이고
 * 정확히 하나의 피연산자가 {@link Origin#DB_READ}(컬럼 식별 가능)이면, 반대편 피연산자의 구체값(리터럴 또는
 * candidateBody의 jsonPath 조회값)을 기준으로 가드 조건이 거짓이 되는 경계값을 계산해
 * {@code {"seed.sql": {"column": ..., "value": ...}}} 패치를 제안한다. <b>가정(문서화된 휴리스틱)</b>: 가드
 * 조건이 참일 때 실패(throw/4xx)로 이어진다고 가정한다 — {@code GuardFact}가 then/else 극성을 기록하지
 * 않으므로(Task 8 minor-deferred) 반대 극성 SUT에서는 제안이 부정확할 수 있다(best-effort, 정확성 보장 아님).
 * 반대편 값을 구할 수 없으면(리터럴도 없고 jsonPath도 candidateBody에 없으면) 제안 없이 {@code null}이다.
 */
public record FailureDigest(int status, String outcomeKind, JsonNode responseBody,
                            String logExcerpt, String stackExcerpt,
                            String mappedGuard, ObjectNode toolSuggestion) {

    private static final Pattern STACK_FRAME =
            Pattern.compile("at\\s+[\\w.$]+\\.[\\w$<>]+\\(([^():]+):(\\d+)\\)");
    private static final Set<String> NUMERIC_OPS = Set.of(">", ">=", "<", "<=");

    /**
     * REQ-014 산출 진입점. {@code candidateBody}는 toolSuggestion의 경계값 계산에만 쓰인다(마킹 대상
     * 아님 — 응답/로그 원문은 별도 인자로 그대로 보존).
     */
    public static FailureDigest of(int status, Outcome.Kind outcomeKind, JsonNode candidateBody,
            JsonNode responseBody, String logExcerpt, String stackExcerpt, ProvenanceReport report) {
        GuardFact guard = mapToGuard(stackExcerpt, responseBody, logExcerpt, report);
        String mappedGuardId = guard == null ? null : (guard.op() + "@" + guard.at());
        ObjectNode suggestion = guard == null ? null : suggestPatch(guard, candidateBody);
        return new FailureDigest(status, outcomeKind.name(), responseBody, logExcerpt, stackExcerpt,
                mappedGuardId, suggestion);
    }

    /**
     * invoke 이전 단계(② seed.sql INSERT, ③ stub 등록)에서 던져진 예외를 기록하는 다이제스트(trial
     * CLI 루프의 후보 단위 격리 — 이 후보만 실패 처리하고 다음 후보로 진행하기 위한 최소 산출물).
     * HTTP 응답이 아예 없었으므로 {@code status=-1}(sentinel), {@code outcomeKind="ERROR"}(SUCCESS/
     * FAILURE 밖 실행 오류 표시)로 표기하고, mappedGuard/toolSuggestion은 응답/판정이 없어 계산
     * 불가하므로 {@code null}이다. {@code stackExcerpt}에 예외의 전체 스택트레이스를 담는다.
     */
    public static FailureDigest forError(String context, Throwable cause) {
        java.io.StringWriter sw = new java.io.StringWriter();
        cause.printStackTrace(new java.io.PrintWriter(sw));
        String logExcerpt = (context == null ? "" : context + ": ") + cause;
        return new FailureDigest(-1, "ERROR", null, logExcerpt, sw.toString(), null, null);
    }

    static GuardFact mapToGuard(String stackExcerpt, JsonNode responseBody, String logExcerpt,
            ProvenanceReport report) {
        if (report == null || report.guards() == null) {
            return null;
        }
        if (stackExcerpt != null && !stackExcerpt.isBlank()) {
            Matcher m = STACK_FRAME.matcher(stackExcerpt);
            while (m.find()) {
                String fileLine = m.group(1) + ":" + m.group(2);
                for (GuardFact g : report.guards()) {
                    if (fileLine.equals(g.at())) {
                        return g;
                    }
                }
            }
        }
        String haystack = (responseBody == null ? "" : responseBody.toString())
                + "\n" + (logExcerpt == null ? "" : logExcerpt);
        for (GuardFact g : report.guards()) {
            for (ValueRef v : g.operands()) {
                String literal = v.literal();
                if (literal != null && !literal.isBlank() && haystack.contains(literal)) {
                    return g;
                }
            }
        }
        return null;
    }

    private static ObjectNode suggestPatch(GuardFact guard, JsonNode candidateBody) {
        if (!NUMERIC_OPS.contains(guard.op()) || guard.operands().size() != 2) {
            return null;
        }
        ValueRef left = guard.operands().get(0);
        ValueRef right = guard.operands().get(1);
        ValueRef dbOperand;
        ValueRef otherOperand;
        boolean dbIsLeft;
        if (left.origin() == Origin.DB_READ && left.column() != null) {
            dbOperand = left;
            otherOperand = right;
            dbIsLeft = true;
        } else if (right.origin() == Origin.DB_READ && right.column() != null) {
            dbOperand = right;
            otherOperand = left;
            dbIsLeft = false;
        } else {
            return null;
        }
        Long otherValue = resolveNumeric(otherOperand, candidateBody);
        if (otherValue == null) {
            return null;
        }
        long patched = boundaryValue(guard.op(), dbIsLeft, otherValue);
        ObjectNode seedPatch = Json.mapper().createObjectNode();
        seedPatch.put("column", dbOperand.column());
        seedPatch.put("value", patched);
        ObjectNode root = Json.mapper().createObjectNode();
        root.set("seed.sql", seedPatch);
        return root;
    }

    /**
     * DB_READ 쪽이 조건을 만족(guard 조건=거짓)하도록 만드는 경계값. dbIsLeft={@code db OP other},
     * !dbIsLeft={@code other OP db}. 등호 경계는 안전 쪽(≥/≤ 그대로 통과)으로 맞춘다.
     */
    private static long boundaryValue(String op, boolean dbIsLeft, long other) {
        if (dbIsLeft) {
            return switch (op) {
                case ">" -> other;
                case "<" -> other;
                case ">=" -> other - 1;
                default -> other + 1;   // "<="
            };
        }
        return switch (op) {
            case ">" -> other;
            case "<" -> other;
            case ">=" -> other + 1;
            default -> other - 1;   // "<="
        };
    }

    private static Long resolveNumeric(ValueRef ref, JsonNode candidateBody) {
        if (ref.literal() != null) {
            Long lit = parseLongOrNull(ref.literal());
            if (lit != null) {
                return lit;
            }
        }
        if (ref.jsonPath() != null && candidateBody != null) {
            JsonNode node = candidateBody.at(toJsonPointer(ref.jsonPath()));
            if (!node.isMissingNode() && node.isNumber()) {
                return node.asLong();
            }
        }
        return null;
    }

    private static JsonPointer toJsonPointer(String dotPath) {
        return JsonPointer.compile("/" + dotPath.replace('.', '/'));
    }

    private static Long parseLongOrNull(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
