package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T1 후보 검증 게이트 — 마커-diff(REQ-009), seed.sql 화이트리스트({@link SeedSqlWhitelist}, REQ-010),
 * 스키마 검증(REQ-011), PII 휴리스틱 차단(REQ-012)을 한 후보에 대해 종합 판정한다.
 *
 * <p>물리 스키마({@code tables}, FK 전이 폐포용)와 SUT DB 방언({@code dialect})은 빌드 세션 동안 바뀌지
 * 않는 구성값이므로 생성자로 주입한다 — {@link #validate}는 후보별로 반복 호출되는 호출 계약이므로
 * (candidateDir, toolBaseDir, report, shape) 네 인자만 받는다.
 */
public final class TripleValidator {

    /** 종합 판정 결과. {@code needsHumanReview=true}면 PII 히트를 의미하며 승격이 차단된다(accepted=false 동반). */
    public record ValidationResult(boolean accepted, boolean needsHumanReview, List<String> reasons) {
    }

    private static final String GAP_MARKER_PREFIX = TripleSynthesizer.GAP_MARKER_PREFIX;

    // REQ-012 PII 패턴(brief 명시 정규식 그대로) — 한국 휴대전화 / 주민등록번호 / 실도메인 이메일.
    private static final Pattern PHONE = Pattern.compile("01\\d-?\\d{3,4}-?\\d{4}");
    private static final Pattern RESIDENT_REGISTRATION_NUMBER = Pattern.compile("\\d{6}-?[1-4]\\d{6}");
    private static final Pattern REAL_EMAIL_DOMAIN = Pattern.compile("@(gmail|naver|daum|kakao)\\.com");

    // REQ-011: WireMock mapping 스키마 고정 키 집합(TripleSynthesizer가 산출하는 stub 구조와 일치).
    private static final Set<String> STUB_TOP_KEYS = Set.of("request", "response");
    private static final Set<String> STUB_REQUEST_KEYS = Set.of("method", "urlPath");
    private static final Set<String> STUB_RESPONSE_KEYS = Set.of("status", "jsonBody");

    private final List<TableSchema> tables;
    private final DbConfig.Type dialect;
    private final SeedSqlWhitelist whitelist = new SeedSqlWhitelist();

    public TripleValidator(List<TableSchema> tables, DbConfig.Type dialect) {
        this.tables = tables;
        this.dialect = dialect;
    }

    public ValidationResult validate(Path candidateDir, Path toolBaseDir, ProvenanceReport report, BodyShape shape) {
        List<String> reasons = new ArrayList<>();
        List<String> markerFilledValues = new ArrayList<>();
        boolean needsHumanReview = false;

        try {
            JsonNode baseBody = readJson(toolBaseDir.resolve("body.json"));
            JsonNode candBody = readJson(candidateDir.resolve("body.json"));
            JsonNode baseStubs = readJson(toolBaseDir.resolve("stubs.json"));
            JsonNode candStubs = readJson(candidateDir.resolve("stubs.json"));
            String baseSeed = Files.readString(toolBaseDir.resolve("seed.sql"));
            String candSeed = Files.readString(candidateDir.resolve("seed.sql"));

            // REQ-009: 마커-diff (notes.md는 검사 대상이 아니다 — 읽지 않음)
            diffJson(baseBody, candBody, "body", reasons, markerFilledValues);
            diffJson(baseStubs, candStubs, "stubs", reasons, markerFilledValues);
            diffSeedSql(baseSeed, candSeed, reasons, markerFilledValues);

            // REQ-010: seed.sql 화이트리스트(DB_READ 테이블 + FK 전이 폐포)
            Set<String> whitelistedTables = SeedSqlWhitelist.transitiveWhitelist(dbReadTables(report), tables);
            SeedSqlWhitelist.WhitelistResult wl = whitelist.validate(candSeed, whitelistedTables, dialect);
            if (!wl.accepted()) {
                reasons.addAll(wl.reasons());
            }

            // REQ-011: 스키마 검증(body는 BodyShape, stub은 WireMock mapping 스키마)
            reasons.addAll(schemaViolationsForBody(candBody, shape));
            reasons.addAll(schemaViolationsForStub(candStubs));

            // REQ-012: 마커 위치에 채워진 값만 PII 스캔(경고-통과 금지 — 히트 시 승격 차단)
            for (String value : markerFilledValues) {
                if (containsPii(value)) {
                    needsHumanReview = true;
                    reasons.add("PII 패턴 히트(REQ-012, 승격 차단 — needsHumanReview): 마커에 채워진 값이 "
                            + "실데이터 형식(휴대전화/주민등록번호/실도메인 이메일)과 일치함");
                }
            }
        } catch (IOException e) {
            reasons.add("후보 아티팩트 로드 실패: " + e.getMessage());
        }

        boolean accepted = reasons.isEmpty();
        return new ValidationResult(accepted, needsHumanReview, reasons);
    }

    // ---- REQ-009: body/stubs JSON 마커-diff ----

    /**
     * base와 candidate JSON 트리를 재귀 비교한다. base의 리프가 갭 마커 문자열이면 해당 위치는 스칼라
     * (문자열/숫자/불리언) 값으로 바뀌는 것만 허용하고(candidate의 스칼라 값을 PII 스캔 대상으로 수집),
     * 그 외 위치는 완전히 동일해야 한다(키 추가/삭제, 배열 크기 변경, 스칼라 값 변경 모두 reject).
     *
     * <p><b>마커 위치의 스칼라 강제(REQ-011):</b> 마커는 "값 치환" 계약이지 "구조 대체"가 아니다 — 후보가
     * 마커 자리를 객체·배열(예: stub {@code jsonBody}의 마커를 중첩 객체로 대체)로 바꾸면, 그 서브트리는
     * body/stub 스키마가 전혀 모르는 임의 필드를 몰래 들여오는 경로가 되므로 reject한다.
     */
    private static void diffJson(JsonNode base, JsonNode cand, String path, List<String> reasons,
                                  List<String> markerFilledValues) {
        if (base == null || cand == null) {
            reasons.add("마커 계약 위반(REQ-009) — 아티팩트 노드 누락: " + path);
            return;
        }
        if (isMarkerNode(base)) {
            if (cand.isObject() || cand.isArray()) {
                reasons.add("마커 계약 위반(REQ-011, 마커 위치가 스칼라가 아닌 구조로 대체됨) at " + path
                        + ": " + cand.getNodeType());
                return;
            }
            collectScalarValues(cand, markerFilledValues);
            return;
        }
        if (base.isObject() && cand.isObject()) {
            Set<String> baseKeys = new LinkedHashSet<>();
            base.fieldNames().forEachRemaining(baseKeys::add);
            Set<String> candKeys = new LinkedHashSet<>();
            cand.fieldNames().forEachRemaining(candKeys::add);
            if (!baseKeys.equals(candKeys)) {
                reasons.add("마커 계약 위반(REQ-009, 키 추가/삭제) at " + path
                        + ": base=" + baseKeys + " candidate=" + candKeys);
                return;
            }
            for (String key : baseKeys) {
                diffJson(base.get(key), cand.get(key), path + "." + key, reasons, markerFilledValues);
            }
        } else if (base.isArray() && cand.isArray()) {
            if (base.size() != cand.size()) {
                reasons.add("마커 계약 위반(REQ-009, 배열 크기 변경) at " + path);
                return;
            }
            for (int i = 0; i < base.size(); i++) {
                diffJson(base.get(i), cand.get(i), path + "[" + i + "]", reasons, markerFilledValues);
            }
        } else if (!base.equals(cand)) {
            reasons.add("마커 계약 위반(REQ-009, 비-마커 값 변경) at " + path + ": " + base + " -> " + cand);
        }
    }

    private static boolean isMarkerNode(JsonNode base) {
        return base.isTextual() && base.asText().startsWith(GAP_MARKER_PREFIX);
    }

    private static void collectScalarValues(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectScalarValues(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> collectScalarValues(n, out));
        } else {
            out.add(node.asText());
        }
    }

    // ---- REQ-009: seed.sql 마커-diff(JSqlParser 정규화 비교) ----

    private void diffSeedSql(String baseSql, String candSql, List<String> reasons, List<String> markerFilledValues) {
        LinkedHashMap<String, LinkedHashMap<String, LiteralValue>> baseRows =
                extractRows(baseSql, "base", reasons);
        LinkedHashMap<String, LinkedHashMap<String, LiteralValue>> candRows =
                extractRows(candSql, "candidate", reasons);

        if (!baseRows.keySet().equals(candRows.keySet())) {
            reasons.add("마커 계약 위반(REQ-009, seed.sql 테이블 집합 변경): base=" + baseRows.keySet()
                    + " candidate=" + candRows.keySet());
            return;
        }
        for (String table : baseRows.keySet()) {
            LinkedHashMap<String, LiteralValue> baseRow = baseRows.get(table);
            LinkedHashMap<String, LiteralValue> candRow = candRows.get(table);
            if (!baseRow.keySet().equals(candRow.keySet())) {
                reasons.add("마커 계약 위반(REQ-009, seed.sql 컬럼 집합 변경) at " + table + ": base="
                        + baseRow.keySet() + " candidate=" + candRow.keySet());
                continue;
            }
            for (String column : baseRow.keySet()) {
                LiteralValue baseVal = baseRow.get(column);
                LiteralValue candVal = candRow.get(column);
                if (baseVal.value().equals(candVal.value())) {
                    continue;
                }
                if (baseVal.marker()) {
                    markerFilledValues.add(candVal.value());
                } else {
                    reasons.add("마커 계약 위반(REQ-009, seed.sql 비-마커 컬럼 값 변경) at " + table + "." + column
                            + ": " + baseVal.value() + " -> " + candVal.value());
                }
            }
        }
    }

    /** seed.sql 각 줄을 파싱해 {@code table -> (column -> 리터럴)} 맵으로 변환한다. 파싱 실패 줄은 skip(사유는 이미 기록됨). */
    private LinkedHashMap<String, LinkedHashMap<String, LiteralValue>> extractRows(
            String sql, String label, List<String> reasons) {
        LinkedHashMap<String, LinkedHashMap<String, LiteralValue>> rows = new LinkedHashMap<>();
        for (String line : SeedSqlWhitelist.nonBlankLines(sql)) {
            Optional<Insert> maybeInsert = whitelist.parseSingleInsert(line, dialect, reasons);
            if (maybeInsert.isEmpty()) {
                continue;
            }
            Insert insert = maybeInsert.get();
            String table = insert.getTable().getUnquotedName();
            List<Column> columns = insert.getColumns();
            List<? extends Expression> values = insert.getValues() == null
                    ? null : insert.getValues().getExpressions();
            if (columns == null || values == null || columns.size() != values.size()) {
                reasons.add("seed.sql INSERT 컬럼/값 개수 불일치(" + label + "): " + line);
                continue;
            }
            LinkedHashMap<String, LiteralValue> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).getUnquotedColumnName(), toLiteralValue(values.get(i)));
            }
            rows.put(table, row);
        }
        return rows;
    }

    /** {@code (정규화 문자열, 마커 여부)}. 문자열 리터럴은 따옴표 제거값을, 그 외(숫자 등)는 {@code toString()}을 쓴다. */
    private record LiteralValue(String value, boolean marker) {
    }

    private static LiteralValue toLiteralValue(Expression expr) {
        if (expr instanceof StringValue) {
            String raw = ((StringValue) expr).getValue();
            return new LiteralValue(raw, raw.startsWith(GAP_MARKER_PREFIX));
        }
        return new LiteralValue(expr.toString(), false);
    }

    // ---- REQ-011: 스키마 검증 ----

    private static List<String> schemaViolationsForBody(JsonNode body, BodyShape shape) {
        List<String> violations = new ArrayList<>();
        Set<String> allowed = new LinkedHashSet<>();
        for (BodyShape.BodyField field : shape.fields()) {
            allowed.add(field.name());
        }
        if (allowed.isEmpty() || body == null) {
            // BodyShape 미상(BodyShape.empty() 등) — 형상 정보가 없으면 과잉 reject를 피하고 skip한다.
            return violations;
        }
        Set<String> actualLeafPaths = new LinkedHashSet<>();
        collectLeafPaths(body, "", actualLeafPaths);
        for (String leafPath : actualLeafPaths) {
            if (!allowed.contains(leafPath)) {
                violations.add("body 필드가 BodyShape에 없음(REQ-011 스키마 위반): " + leafPath);
            }
        }
        return violations;
    }

    private static void collectLeafPaths(JsonNode node, String prefix, Set<String> out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    collectLeafPaths(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out));
        } else if (node.isArray()) {
            node.forEach(el -> collectLeafPaths(el, prefix, out));
        } else if (!prefix.isEmpty()) {
            out.add(prefix);
        }
    }

    private static List<String> schemaViolationsForStub(JsonNode stub) {
        List<String> violations = new ArrayList<>();
        if (stub == null || stub.isEmpty()) {
            return violations;   // 후보에 EXTERNAL_RESPONSE 스텁이 없음 — 검증 대상 없음
        }
        checkKeys(stub, STUB_TOP_KEYS, "stub", violations);
        JsonNode request = stub.get("request");
        if (request != null && request.isObject()) {
            checkKeys(request, STUB_REQUEST_KEYS, "stub.request", violations);
        }
        JsonNode response = stub.get("response");
        if (response != null && response.isObject()) {
            checkKeys(response, STUB_RESPONSE_KEYS, "stub.response", violations);
        }
        return violations;
    }

    private static void checkKeys(JsonNode node, Set<String> allowedKeys, String label, List<String> violations) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowedKeys.contains(key)) {
                violations.add(label + "에 WireMock mapping 스키마 밖 키(REQ-011 스키마 위반): " + key);
            }
        });
    }

    // ---- REQ-012: PII 휴리스틱 ----

    private static boolean containsPii(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return PHONE.matcher(value).find()
                || RESIDENT_REGISTRATION_NUMBER.matcher(value).find()
                || REAL_EMAIL_DOMAIN.matcher(value).find();
    }

    // ---- 공용 ----

    private static Set<String> dbReadTables(ProvenanceReport report) {
        Set<String> result = new LinkedHashSet<>();
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() == Origin.DB_READ && v.table() != null) {
                    result.add(v.table());
                }
            }
        }
        return result;
    }

    private static JsonNode readJson(Path path) throws IOException {
        return Json.mapper().readTree(path.toFile());
    }
}
