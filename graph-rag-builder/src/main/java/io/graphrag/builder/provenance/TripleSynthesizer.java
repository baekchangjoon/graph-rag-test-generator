package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.JsonPaths;
import io.graphrag.builder.oracle.InputCandidates;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.UnguardedField;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProvenanceReport}의 가드 사실(GuardFact)을 출처별로 라우팅해 {@link TripleCandidate}(요청
 * body + seed SQL + stub mapping)를 합성한다 (REQ-005 코어 + REQ-006).
 *
 * <p><b>라우팅(REQ-005 코어):</b> 가드 피연산자의 {@link Origin}에 따라 INPUT은 {@code body}(JSON)에,
 * DB_READ는 seed {@code INSERT} 문장에, EXTERNAL_RESPONSE는 WireMock 스타일 stub mapping에 배치한다.
 *
 * <p><b>공동 배치·경계 만족값(REQ-006):</b>
 * <ul>
 *   <li>존재 가드({@code EXISTS}, 예: {@code repo.findById(req.id()).orElseThrow(...)}) — 코드 패턴상
 *       "성공하려면 반드시 존재"가 직접적인 요구사항이므로, 같은 값 s를 {@code body[jsonPath]}와 seed
 *       INSERT의 PK 컬럼에 동시 배치한다.</li>
 *   <li>비교 가드(예: {@code if (balance < amount) throw ...}) — {@code ProvenanceIndexer}는 "성공하면
 *       반드시 false여야 하는" throw 분기 조건을 그대로 {@code op}에 담으므로(가드 조건 op="&lt;"이면
 *       그 조건이 참일 때 예외), happy-path 후보는 op를 <b>부정(negate)</b>한 관계를 만족해야 한다
 *       (op="&lt;" → 필요 관계 "&gt;="). 부정 관계가 등가를 포함하면(GE/LE/EQ) 결정적 동치 값(예: 100=100)을
 *       우선한다({@link #satisfyingPair}).</li>
 *   <li>FK NOT NULL 부모 행 — seed 대상 테이블의 {@link TableSchema#foreignKeys()} 중 NOT NULL 컬럼에
 *       매핑된 FK는 부모 테이블 행도 재귀적으로 합성한다({@link #fillTable}).</li>
 * </ul>
 *
 * <p><b>갭 마커(REQ-007):</b> 결정적으로 도출 불가한 위치만 표기한다 — {@code unguarded} 필드는
 * body(JSON 문자열 값), NOT NULL이지만 어떤 가드도 값을 결정하지 못한 numeric 컬럼은 seed.sql(작은따옴표
 * 문자열 리터럴, 컬럼 타입 무관 — SQL 파싱 유지), 만족 리터럴을 찾지 못한 EXTERNAL_RESPONSE는
 * stubs.json의 {@code response.jsonBody} 값. 문법: {@code __AGENT_FILL__{type:<T>, semanticHint:<H>,
 * guard:<G>}} — 미상 필드는 {@code none}.
 *
 * <p><b>stubs.json = WireMock mapping 스키마(REQ-008):</b> {@code {"request":{"method","urlPath"},
 * "response":{"status","jsonBody"}}} — {@code HttpCaptureServer.loadStubs}가 쓰는
 * {@code StubMapping.buildFrom(json)}으로 그대로 로드 가능하다. {@code callSite}가
 * {@code "<HTTP메서드> <path>"} 형식이 아니면(class#method 폴백 등) stub을 만들지 않고 notes에 사유를
 * 남긴다.
 *
 * <p><b>후보 cap·정렬(REQ-033):</b> {@code unguarded} 필드마다 갭 마커(미결정) 옵션에 더해
 * {@link InputCandidates}가 제공하는 결정값 옵션들을 더한 뒤 필드별 옵션의 cross product로 후보
 * 조합을 만들고, "결정 필드 수 내림차순 → 정규화 문자열 사전순"으로 정렬해 상위 {@link #CANDIDATE_CAP}개만
 * 반환한다(cand-01=최우선). {@code unguarded}가 없거나 오라클 후보가 없으면 조합은 정확히 1개(기존
 * Task 8 동작과 하위호환).
 *
 * <p><b>남은 확장 지점(Task 9+ 백로그):</b> {@code &&}/{@code ||} 등 결합 논리 가드의 다중 피연산자
 * 라우팅. {@link InputCandidates} DERIVED 해 배치(REQ-032 잔여 절반)는 {@code ProvenanceIndexer}가
 * DERIVED {@link ValueRef}의 {@code jsonPath}를 의도적으로 비워두므로(REQ-001 unguarded 오탐 방지)
 * 이 클래스만으로는 오라클 해를 어느 body 필드에 배치할지 결정적으로 복원할 수 없다 — ValueRef 스키마
 * 확장 없이는 미해결.
 * <b>동일 테이블 다중 행(예: from/to 계좌처럼 같은 테이블을 서로 다른 행으로 참조하는 경우)은 현재
 * 테이블당 한 행으로 병합되어 구분되지 않는다 — Task 9+ 백로그(REQ-006 범위에서는 구조 일반화 보류).</b>
 */
public final class TripleSynthesizer {

    /** 비교 가드 boundary 만족값의 결정적 앵커(수치). 브리프 예시("GE면 col=input=100")를 그대로 채택. */
    private static final long NUMERIC_ANCHOR = 100L;

    /** EP당 후보 cap(REQ-033 기본값). */
    private static final int CANDIDATE_CAP = 4;

    /** 필드별 오라클 후보값 중 조합에 포함할 최대 개수(조합 폭발 방지 — 어차피 최종 cap 이내만 살아남는다). */
    private static final int MAX_OPTIONS_PER_FIELD = CANDIDATE_CAP;

    /** 전체 조합 수 안전 상한. 초과 시 오라클 변주를 포기하고 갭 마커 단일 조합으로 폴백한다(회귀·성능 안전판). */
    private static final long SAFE_COMBO_LIMIT = 4096L;

    /**
     * 갭 마커 접두. body/stubs jsonBody는 이 문자열을 JSON 문자열 값으로, seed.sql은 작은따옴표 리터럴로
     * 담는다. {@link TripleValidator}/{@link SeedSqlWhitelist}(T1 검증 게이트, REQ-009/012)가 마커
     * 위치를 판별할 때도 이 상수를 그대로 참조한다(갭 마커 문법의 단일 소스).
     */
    public static final String GAP_MARKER_PREFIX = "__AGENT_FILL__";

    /** EXTERNAL_RESPONSE callSite가 {@code "<HTTP메서드> <path>"} 형식인지 판정할 때 허용하는 메서드 집합. */
    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static final Set<String> NUMERIC_JAVA_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger");

    /** 좌우 피연산자 관계(원본 소스 조건과 동일 극성). {@link #negate()}로 happy-path 관계를 얻는다. */
    private enum Rel {
        LT, LE, GT, GE, EQ, NE;

        static Rel fromSymbol(String op) {
            return switch (op) {
                case "<" -> LT;
                case "<=" -> LE;
                case ">" -> GT;
                case ">=" -> GE;
                case "==" -> EQ;
                case "!=" -> NE;
                default -> null;
            };
        }

        Rel negate() {
            return switch (this) {
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
                case EQ -> NE;
                case NE -> EQ;
            };
        }
    }

    /**
     * 리포트로부터 후보 트리플 목록을 합성한다(cap 이내, cand-01=최우선 순서 — REQ-033). guard가 결정한
     * 값은 모든 후보에서 동일하고, {@code unguarded} 필드만 갭 마커/오라클 결정값으로 조합에 따라 달라진다.
     *
     * @param report 분석 대상 엔드포인트의 provenance 리포트(가드/unguarded/unresolved)
     * @param shape  {@code @RequestBody} 타입의 필드 구조 (현재 라우팅은 가드 피연산자의 jsonPath만
     *               사용하므로 직접 참조하지 않지만, unguarded 필드의 body 배치를 추가할 후속 task의
     *               확장 지점으로 시그니처에 유지한다)
     * @param tables seed 대상 물리 스키마 목록(FK 부모 탐색 포함)
     * @param oracle unguarded 필드의 결정값 후보(REQ-033 조합 생성에 소비). DERIVED 배치(REQ-032 잔여)는
     *               클래스 Javadoc의 "남은 확장 지점" 참조 — 아직 미해결.
     */
    public List<TripleCandidate> synthesize(ProvenanceReport report, BodyShape shape,
                                            List<TableSchema> tables, InputCandidates oracle) {
        ObjectNode body = Json.mapper().createObjectNode();
        List<ObjectNode> stubMappings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, TableSchema> tablesByName = new LinkedHashMap<>();
        for (TableSchema t : tables) {
            tablesByName.put(t.name(), t);
        }
        // seed 대상 테이블별 컬럼→값 배정 (co-location: 같은 테이블에 여러 가드가 값을 보태면 한 행에 합쳐진다).
        Map<String, LinkedHashMap<String, Object>> rowsByTable = new LinkedHashMap<>();
        // 확장 지점 명시(클래스 Javadoc과 동일 내용) — 동일 테이블을 서로 다른 행으로 구분해야 하는
        // 경우(from/to 계좌 등)는 현재 1행으로 병합된다.
        notes.add("확장 지점: 동일 테이블 다중 행(from/to 계좌류)은 현재 1행으로 병합됨(Task 9+ 백로그)");

        String primaryTable = solePrimaryTable(report);

        for (GuardFact guard : report.guards()) {
            switch (guard.op()) {
                case "EXISTS" -> routeExistsGuard(guard, primaryTable, tablesByName, rowsByTable, body, notes);
                case "<", "<=", ">", ">=", "==", "!=" ->
                        routeComparisonGuard(guard, tablesByName, rowsByTable, body, notes);
                case "!" -> routeNegatedEqualityGuard(guard, stubMappings, notes);
                default -> notes.add("op '" + guard.op() + "' at " + guard.at()
                        + " — 결합 논리/미지원 가드 라우팅은 후속 task 범위(확장 지점)");
            }
        }

        List<String> seedSqlStatements = finalizeSeedRows(rowsByTable, tablesByName, notes);

        if (oracle != null && (!oracle.numeric().isEmpty() || !oracle.strings().isEmpty())) {
            notes.add("InputCandidates 오라클 " + (oracle.numeric().size() + oracle.strings().size())
                    + "개 필드 후보 보유 — unguarded 필드 조합에 소비(REQ-032 잔여 DERIVED 배치는 미해결, 클래스 Javadoc 참조)");
        }

        return buildCandidates(body, seedSqlStatements, stubMappings, notes, report.unguarded(), oracle);
    }

    /**
     * {@code unguarded} 필드별 옵션(갭 마커 + 오라클 결정값)의 cross product로 후보 조합을 만들고,
     * "결정 필드 수 내림차순 → 정규화 키 사전순"으로 정렬해 상위 {@link #CANDIDATE_CAP}개를 반환한다
     * (REQ-033). {@code unguarded}가 비었거나 오라클 후보가 전혀 없으면 조합은 정확히 1개
     * (Task 8과 동일한 단일 후보 동작 — 기존 테스트 하위호환).
     */
    private List<TripleCandidate> buildCandidates(ObjectNode baseBody, List<String> seedSqlStatements,
                                                  List<ObjectNode> stubMappings, List<String> baseNotes,
                                                  List<UnguardedField> unguarded, InputCandidates oracle) {
        List<List<FieldOption>> perFieldOptions = new ArrayList<>();
        for (UnguardedField field : unguarded) {
            perFieldOptions.add(optionsFor(field, oracle));
        }

        List<List<FieldOption>> combos = crossProductWithSafetyCap(perFieldOptions, baseNotes);

        List<List<FieldOption>> ranked = new ArrayList<>(combos);
        ranked.sort(Comparator
                .comparingInt(TripleSynthesizer::decidedCount).reversed()
                .thenComparing(TripleSynthesizer::comboSortKey));

        List<TripleCandidate> out = new ArrayList<>();
        int limit = Math.min(CANDIDATE_CAP, ranked.size());
        for (int i = 0; i < limit; i++) {
            List<FieldOption> combo = ranked.get(i);
            ObjectNode body = baseBody.deepCopy();
            List<String> notes = new ArrayList<>(baseNotes);
            for (int f = 0; f < unguarded.size(); f++) {
                UnguardedField field = unguarded.get(f);
                FieldOption option = combo.get(f);
                putBodyValue(body, field.jsonPath(), option.value());
                notes.add((option.decided() ? "unguarded(" + field.jsonPath() + ") -> 오라클 결정값 " + option.value()
                        : "unguarded(" + field.jsonPath() + ") -> 갭 마커(도출 불가, semanticHint="
                                + (field.semanticHint() == null ? "none" : field.semanticHint()) + ")"));
            }
            notes.add(0, "cand-" + String.format("%02d", i + 1) + ": 결정 필드 " + decidedCount(combo) + "/"
                    + unguarded.size() + "(unguarded 기준)");
            out.add(new TripleCandidate(body, seedSqlStatements, stubMappings, String.join("\n", notes)));
        }
        return out;
    }

    /** 후보 조합의 필드별 배정 하나: {@code decided}=true면 오라클 결정값, false면 갭 마커. */
    private record FieldOption(Object value, boolean decided) {
    }

    private static int decidedCount(List<FieldOption> combo) {
        int n = 0;
        for (FieldOption o : combo) {
            if (o.decided()) {
                n++;
            }
        }
        return n;
    }

    /** 결정 필드 수가 같은 조합끼리의 결정적 tie-break 키(값들을 필드 순서대로 이어붙인 사전순 문자열). */
    private static String comboSortKey(List<FieldOption> combo) {
        StringBuilder sb = new StringBuilder();
        for (FieldOption o : combo) {
            sb.append(String.valueOf(o.value())).append(';');
        }
        return sb.toString();
    }

    /** unguarded 필드 하나의 옵션 목록: 항상 갭 마커 1개 + 오라클 결정값(있으면, 필드명은 jsonPath의 마지막 세그먼트). */
    private List<FieldOption> optionsFor(UnguardedField field, InputCandidates oracle) {
        List<FieldOption> options = new ArrayList<>();
        options.add(new FieldOption(gapMarker(field.javaType(), field.semanticHint(), "none"), false));
        if (oracle == null) {
            return options;
        }
        String key = simpleFieldName(field.jsonPath());
        List<Object> decided = new ArrayList<>();
        Set<Long> numeric = oracle.numeric().get(key);
        if (numeric != null) {
            decided.addAll(new java.util.TreeSet<>(numeric));
        }
        Set<String> strings = oracle.strings().get(key);
        if (strings != null) {
            decided.addAll(new java.util.TreeSet<>(strings));
        }
        int limit = Math.min(decided.size(), MAX_OPTIONS_PER_FIELD);
        for (int i = 0; i < limit; i++) {
            options.add(new FieldOption(decided.get(i), true));
        }
        return options;
    }

    private static String simpleFieldName(String jsonPath) {
        int dot = jsonPath.lastIndexOf('.');
        return dot < 0 ? jsonPath : jsonPath.substring(dot + 1);
    }

    /**
     * 필드별 옵션 목록의 cross product. 전체 조합 수가 {@link #SAFE_COMBO_LIMIT}를 넘으면(오라클 후보가
     * 많은 필드가 다수인 병리적 케이스) 조합 폭발을 막기 위해 오라클 변주를 포기하고 필드마다 갭 마커
     * 단일 옵션으로 폴백한다(notes에 사유 기록) — 안전판, REQ-033 정상 케이스는 영향 없음.
     */
    private static List<List<FieldOption>> crossProductWithSafetyCap(List<List<FieldOption>> perFieldOptions,
                                                                      List<String> notes) {
        long total = 1L;
        boolean overflow = false;
        for (List<FieldOption> options : perFieldOptions) {
            total *= options.size();
            if (total > SAFE_COMBO_LIMIT) {
                overflow = true;
                break;
            }
        }
        List<List<FieldOption>> effective = perFieldOptions;
        if (overflow) {
            notes.add("unguarded 조합 수가 안전 상한(" + SAFE_COMBO_LIMIT + ")을 초과 — 오라클 변주 생략, 갭 마커 단일 조합으로 폴백");
            effective = new ArrayList<>();
            for (List<FieldOption> options : perFieldOptions) {
                effective.add(List.of(options.get(0)));   // index 0 = 항상 갭 마커(옵션 생성 순서 불변식)
            }
        }
        List<List<FieldOption>> combos = new ArrayList<>();
        combos.add(new ArrayList<>());
        for (List<FieldOption> fieldOptions : effective) {
            List<List<FieldOption>> next = new ArrayList<>();
            for (List<FieldOption> combo : combos) {
                for (FieldOption option : fieldOptions) {
                    List<FieldOption> extended = new ArrayList<>(combo);
                    extended.add(option);
                    next.add(extended);
                }
            }
            combos = next;
        }
        return combos;
    }

    /** 갭 마커 문자열: {@code __AGENT_FILL__{type:<T>, semanticHint:<H>, guard:<G>}} (REQ-007). */
    private static String gapMarker(String type, String semanticHint, String guard) {
        return GAP_MARKER_PREFIX + "{type:" + (type == null || type.isBlank() ? "Object" : type)
                + ", semanticHint:" + (semanticHint == null || semanticHint.isBlank() ? "none" : semanticHint)
                + ", guard:" + (guard == null || guard.isBlank() ? "none" : guard) + "}";
    }

    /** 리포트 전체에서 DB_READ 테이블이 정확히 하나뿐이면 그 테이블명을 반환(EXISTS의 table 미기재 시 폴백). */
    private static String solePrimaryTable(ProvenanceReport report) {
        Set<String> dbTables = new LinkedHashSet<>();
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() == Origin.DB_READ && v.table() != null) {
                    dbTables.add(v.table());
                }
            }
        }
        return dbTables.size() == 1 ? dbTables.iterator().next() : null;
    }

    /** EXISTS 가드: INPUT jsonPath 값 s를 body와 seed PK에 동시 배치. */
    private void routeExistsGuard(GuardFact guard, String primaryTable, Map<String, TableSchema> tablesByName,
                                  Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                  List<String> notes) {
        for (ValueRef v : guard.operands()) {
            if (v.origin() != Origin.INPUT || v.jsonPath() == null) {
                continue;
            }
            String table = v.table() != null ? v.table() : primaryTable;
            TableSchema schema = table == null ? null : tablesByName.get(table);
            ColumnSchema pk = schema == null ? null : findPrimaryKey(schema);
            if (schema == null || pk == null) {
                notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at()
                        + " — 대상 테이블/PK 미해결, seed 배치 skip(확장 지점)");
                continue;
            }
            Object idValue = deterministicIdValue(v.jsonPath(), v.javaType(), pk);
            putBodyValue(body, v.jsonPath(), idValue);
            rowFor(rowsByTable, table).put(pk.name(), coerceForColumn(idValue, pk));
            notes.add("EXISTS(" + v.jsonPath() + ") at " + guard.at() + " -> body." + v.jsonPath()
                    + " = seed " + table + "." + pk.name() + " = " + idValue);
        }
    }

    /**
     * 비교 가드(DB_READ col OP INPUT, 또는 그 반대 순서): 추출된 op는 throw 분기의 원본 조건이므로
     * 부정 관계를 만족하는 (col값, body값) 쌍을 co-location한다.
     */
    private void routeComparisonGuard(GuardFact guard, Map<String, TableSchema> tablesByName,
                                      Map<String, LinkedHashMap<String, Object>> rowsByTable, ObjectNode body,
                                      List<String> notes) {
        List<ValueRef> operands = guard.operands();
        if (operands.size() != 2) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — 피연산자 2개가 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        ValueRef opA = operands.get(0);
        ValueRef opB = operands.get(1);
        ValueRef dbRef;
        ValueRef inputRef;
        if (opA.origin() == Origin.DB_READ && opB.origin() == Origin.INPUT) {
            dbRef = opA;
            inputRef = opB;
        } else if (opA.origin() == Origin.INPUT && opB.origin() == Origin.DB_READ) {
            dbRef = opB;
            inputRef = opA;
        } else {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — DB_READ/INPUT 조합이 아닌 비교 가드는 미지원(확장 지점)");
            return;
        }
        Rel rel = Rel.fromSymbol(guard.op());
        if (rel == null || dbRef.table() == null || dbRef.column() == null || inputRef.jsonPath() == null) {
            notes.add("op '" + guard.op() + "' at " + guard.at()
                    + " — table/column/jsonPath 미해결, 비교 가드 skip(확장 지점)");
            return;
        }
        TableSchema schema = tablesByName.get(dbRef.table());
        ColumnSchema column = schema == null ? null
                : schema.columns().stream().filter(c -> c.name().equals(dbRef.column())).findFirst().orElse(null);

        Rel needed = rel.negate();
        boolean numeric = isNumericJavaType(dbRef.javaType()) || isNumericJavaType(inputRef.javaType());
        Object[] pair = satisfyingPair(needed, numeric);
        Object aVal = pair[0];
        Object bVal = pair[1];
        Object dbVal = dbRef == opA ? aVal : bVal;
        Object inputVal = inputRef == opA ? aVal : bVal;

        putBodyValue(body, inputRef.jsonPath(), inputVal);
        rowFor(rowsByTable, dbRef.table()).put(dbRef.column(), coerceForColumn(dbVal, column));
        notes.add("comparison(" + opA.origin() + " " + guard.op() + " " + opB.origin() + ") at " + guard.at()
                + " negated-to=" + needed + " -> body." + inputRef.jsonPath() + "=" + inputVal
                + ", seed " + dbRef.table() + "." + dbRef.column() + "=" + dbVal);
    }

    /**
     * {@code !x.equals(y)} 패턴(리터럴 vs EXTERNAL_RESPONSE)의 부정 등가 가드: happy path는 등가이므로
     * stubField에 만족값(리터럴이 있으면 그 값, 없으면 갭 마커)을 채운 WireMock mapping을 만든다(REQ-008).
     * {@code callSite}가 {@code "<HTTP메서드> <path>"} 형식이 아니면(class#method 폴백 등 미해결
     * 위치) stub을 만들지 않고 notes에 사유를 남긴다 — 형식을 알 수 없는 request.method/urlPath로 잘못된
     * mapping을 만드는 것보다 안전하다.
     */
    private void routeNegatedEqualityGuard(GuardFact guard, List<ObjectNode> stubMappings, List<String> notes) {
        ValueRef literalRef = null;
        ValueRef externalRef = null;
        for (ValueRef v : guard.operands()) {
            if (v.literal() != null) {
                literalRef = v;
            }
            if (v.origin() == Origin.EXTERNAL_RESPONSE) {
                externalRef = v;
            }
        }
        if (externalRef == null || externalRef.callSite() == null || externalRef.stubField() == null) {
            notes.add("op '!' at " + guard.at() + " — EXTERNAL_RESPONSE 부정 등가 패턴이 아님, stub 라우팅 skip(확장 지점)");
            return;
        }
        String callSite = externalRef.callSite();
        int sp = httpCallSiteSplit(callSite);
        if (sp < 0) {
            notes.add("op '!' at " + guard.at() + " — callSite '" + callSite
                    + "' 가 '<HTTP메서드> <path>' 형식이 아님(class#method 폴백으로 추정), stub 생성 불가");
            return;
        }
        ObjectNode stub = Json.mapper().createObjectNode();
        ObjectNode request = stub.putObject("request");
        request.put("method", callSite.substring(0, sp));
        request.put("urlPath", callSite.substring(sp + 1));
        ObjectNode response = stub.putObject("response");
        response.put("status", 200);
        ObjectNode jsonBody = response.putObject("jsonBody");
        if (literalRef != null && literalRef.literal() != null) {
            jsonBody.put(externalRef.stubField(), literalRef.literal());
            stubMappings.add(stub);
            notes.add("EXTERNAL_RESPONSE(" + callSite + ") at " + guard.at() + " -> stub."
                    + externalRef.stubField() + "=" + literalRef.literal() + " (WireMock mapping 스키마, REQ-008)");
        } else {
            jsonBody.put(externalRef.stubField(), gapMarker(externalRef.javaType(), "none", "none"));
            stubMappings.add(stub);
            notes.add("EXTERNAL_RESPONSE(" + callSite + ") at " + guard.at()
                    + " — 만족 리터럴 미해결, stub." + externalRef.stubField() + " = 갭 마커(REQ-007)");
        }
    }

    /**
     * callSite가 {@code "<HTTP메서드> <path>"} 형식이면 공백 인덱스를, 아니면(class#method 폴백 등)
     * -1을 반환한다. 첫 토큰이 {@link #HTTP_METHODS}에 속하고 나머지가 {@code "/"}로 시작해야 유효하다.
     */
    private static int httpCallSiteSplit(String callSite) {
        int sp = callSite.indexOf(' ');
        if (sp <= 0 || sp == callSite.length() - 1) {
            return -1;
        }
        String method = callSite.substring(0, sp);
        String path = callSite.substring(sp + 1);
        return HTTP_METHODS.contains(method) && path.startsWith("/") ? sp : -1;
    }

    // ---- seed 행 마무리(PK 기본값·FK 부모 재귀·NOT NULL 기본값) ----

    private final LinkedHashSet<String> emitOrder = new LinkedHashSet<>();

    private List<String> finalizeSeedRows(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                          Map<String, TableSchema> tablesByName, List<String> notes) {
        emitOrder.clear();
        List<String> initialTables = new ArrayList<>(rowsByTable.keySet());
        Set<String> visiting = new LinkedHashSet<>();
        for (String table : initialTables) {
            fillTable(table, tablesByName, rowsByTable, visiting, notes);
        }
        List<String> statements = new ArrayList<>();
        for (String table : emitOrder) {
            statements.add(toInsertStatement(table, rowsByTable.get(table)));
        }
        return statements;
    }

    /**
     * 부모(FK 참조 테이블)를 먼저 재귀적으로 채운 뒤 자신을 {@link #emitOrder}에 등록(부모 선행 emission 보장).
     * 부모 스키마가 {@code tablesByName}에 없거나 부모 PK 해결에 실패하면, 그 NOT NULL FK 컬럼에 null을
     * 침묵 삽입하지 않고 컬럼 자체를 행에서 제외하며 {@code notes}에 {@code "unresolved-fk: ..."}로 남긴다.
     */
    private void fillTable(String tableName, Map<String, TableSchema> tablesByName,
                           Map<String, LinkedHashMap<String, Object>> rowsByTable, Set<String> visiting,
                           List<String> notes) {
        if (emitOrder.contains(tableName) || !visiting.add(tableName)) {
            return; // 이미 채움 완료 또는 순환 참조(방어적 종료)
        }
        LinkedHashMap<String, Object> row = rowFor(rowsByTable, tableName);
        TableSchema schema = tablesByName.get(tableName);
        if (schema == null) {
            notes.add("table '" + tableName + "' — 스키마 미제공, PK/FK 기본값 채움 skip(확장 지점)");
            emitOrder.add(tableName);
            visiting.remove(tableName);
            return;
        }
        ColumnSchema pk = findPrimaryKey(schema);
        if (pk != null && !row.containsKey(pk.name())) {
            row.put(pk.name(), deterministicIdValue(tableName, null, pk));
        }
        for (ColumnSchema column : schema.columns()) {
            if (column.primaryKey() || row.containsKey(column.name()) || column.nullable()) {
                continue;
            }
            ForeignKey fk = findForeignKey(column.name(), schema);
            if (fk == null) {
                row.put(column.name(), defaultValueFor(column, tableName, notes));
                continue;
            }
            if (!tablesByName.containsKey(fk.referencedTable())) {
                // 부모 스키마가 tables에 없음 — null을 침묵 삽입하지 않고 컬럼 자체를 INSERT에서 제외한다
                // (NOT NULL 위반 SQL 생성 방지). 근거는 notes에 "unresolved-fk:"로 남겨 추적 가능하게 한다.
                notes.add("unresolved-fk: " + tableName + "." + column.name() + " -> "
                        + fk.referencedTable() + "." + fk.referencedColumn()
                        + " (부모 스키마가 tables에 없음 — NOT NULL 컬럼을 INSERT에서 제외, 침묵 null 금지)");
                continue;
            }
            fillTable(fk.referencedTable(), tablesByName, rowsByTable, visiting, notes);
            LinkedHashMap<String, Object> parentRow = rowsByTable.get(fk.referencedTable());
            Object parentPk = parentRow == null ? null : parentRow.get(fk.referencedColumn());
            if (parentPk == null) {
                // 방어적 이중 점검: 부모 스키마는 있으나 PK가 referencedColumn과 불일치하는 등 예기치 못한 미해결.
                notes.add("unresolved-fk: " + tableName + "." + column.name() + " -> "
                        + fk.referencedTable() + "." + fk.referencedColumn()
                        + " (부모 PK 해결 실패 — NOT NULL 컬럼을 INSERT에서 제외, 침묵 null 금지)");
                continue;
            }
            row.put(column.name(), parentPk);
        }
        emitOrder.add(tableName);
        visiting.remove(tableName);
    }

    private static LinkedHashMap<String, Object> rowFor(Map<String, LinkedHashMap<String, Object>> rowsByTable,
                                                         String table) {
        return rowsByTable.computeIfAbsent(table, t -> new LinkedHashMap<>());
    }

    private static ColumnSchema findPrimaryKey(TableSchema schema) {
        return schema.columns().stream().filter(ColumnSchema::primaryKey).findFirst().orElse(null);
    }

    private static ForeignKey findForeignKey(String columnName, TableSchema schema) {
        return schema.foreignKeys().stream().filter(fk -> fk.column().equals(columnName)).findFirst().orElse(null);
    }

    private static String toInsertStatement(String table, LinkedHashMap<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        StringBuilder values = new StringBuilder();
        boolean first = true;
        for (Object v : row.values()) {
            if (!first) {
                values.append(", ");
            }
            first = false;
            values.append(sqlLiteral(v));
        }
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ");";
    }

    private static String sqlLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    /**
     * NOT NULL·FK가 아닌 컬럼의 기본값. 문자열/불리언/날짜형은 구조적으로 결정 가능한 값(NOT NULL
     * 제약만 만족하면 되는 padding)이므로 그대로 둔다. 그 외(주로 numeric)는 어떤 가드도 값을 정하지
     * 못한 "UNKNOWN 출처"이므로 임의값(예: 1)을 침묵 삽입하지 않고 갭 마커로 표기한다(REQ-007).
     */
    private static Object defaultValueFor(ColumnSchema column, String tableName, List<String> notes) {
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BOOL")) {
            return true;
        }
        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
            return "seed-" + column.name();
        }
        if (type.contains("DATE") || type.contains("TIME")) {
            return "2037-01-01"; // seed 문자열 리터럴(방언 무관 최소 표기) — 정밀 타입 변환은 후속 task
        }
        notes.add("gap-marker: " + tableName + "." + column.name()
                + " (NOT NULL numeric, 어떤 가드도 결정하지 못함) -> __AGENT_FILL__(REQ-007)");
        return gapMarker(numericMarkerType(type), "none", "none");
    }

    /** jdbcType(대문자) → 갭 마커의 {@code type} 라벨(java 원시형 이름 근사). 미분류 숫자형은 안전하게 long. */
    private static String numericMarkerType(String upperJdbcType) {
        if (upperJdbcType.contains("BIGINT")) {
            return "long";
        }
        if (upperJdbcType.contains("DECIMAL") || upperJdbcType.contains("NUMERIC")) {
            return "BigDecimal";
        }
        if (upperJdbcType.contains("DOUBLE") || upperJdbcType.contains("FLOAT") || upperJdbcType.contains("REAL")) {
            return "double";
        }
        if (upperJdbcType.contains("INT")) {
            return "int";
        }
        return "long";
    }

    // ---- 값 합성 유틸 ----

    private static boolean isNumericJavaType(String javaType) {
        return javaType != null && NUMERIC_JAVA_TYPES.contains(javaType);
    }

    /**
     * 필요 관계 {@code needed}를 만족하는 (좌, 우) 결정적 값 쌍. 등가를 포함하는 관계(GE/LE/EQ)는
     * 동치 값을 우선한다(브리프: "GE면 col=input=100 등 동치 우선"). 문자열 순서 비교(GT/LT)는
     * 미지원 — 보수적으로 동치 폴백(확장 지점).
     */
    private static Object[] satisfyingPair(Rel needed, boolean numeric) {
        if (numeric) {
            return switch (needed) {
                case GE, LE, EQ -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR};
                case GT -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
                case LT -> new Object[]{NUMERIC_ANCHOR, NUMERIC_ANCHOR + 1};
                case NE -> new Object[]{NUMERIC_ANCHOR + 1, NUMERIC_ANCHOR};
            };
        }
        return switch (needed) {
            case NE -> new Object[]{"match", "mismatch"};
            default -> new Object[]{"match", "match"};
        };
    }

    /** jsonPath/javaType(+PK 컬럼 타입) 기준 결정적 id 값. body와 seed PK에 동일하게 쓰인다. */
    private static Object deterministicIdValue(String seedKey, String javaType, ColumnSchema pkColumn) {
        boolean numeric = isNumericJavaType(javaType)
                || (pkColumn.jdbcType() != null && pkColumn.jdbcType().toUpperCase().contains("INT"));
        if (numeric) {
            return NUMERIC_ANCHOR + Math.floorMod(seedKey.hashCode(), 900);
        }
        return "seed-" + seedKey.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase();
    }

    private static void putBodyValue(ObjectNode body, String jsonPath, Object value) {
        if (value instanceof Long l) {
            JsonPaths.putPath(body, jsonPath, l);
        } else if (value instanceof Integer i) {
            JsonPaths.putPath(body, jsonPath, i);
        } else if (value instanceof Double d) {
            JsonPaths.putPath(body, jsonPath, d);
        } else if (value instanceof Boolean b) {
            JsonPaths.putPath(body, jsonPath, b);
        } else {
            JsonPaths.putPath(body, jsonPath, String.valueOf(value));
        }
    }

    private static Object coerceForColumn(Object raw, ColumnSchema column) {
        if (column == null || raw == null) {
            return raw;
        }
        String type = column.jdbcType() == null ? "" : column.jdbcType().toUpperCase();
        if (type.contains("BIGINT")) {
            return toLong(raw);
        }
        if (type.contains("INT")) {
            return (int) toLong(raw);
        }
        if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
            return new BigDecimal(raw.toString());
        }
        if (type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) {
            return Double.valueOf(raw.toString());
        }
        if (type.contains("BOOL")) {
            return raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
        }
        return raw.toString();
    }

    private static long toLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
    }
}
