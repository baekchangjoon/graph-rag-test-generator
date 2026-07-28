package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.run.SynthesizedInput;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.TableSchema;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * T3 파이프라인 통합 게이트(REQ-018/019/020/035) — {@code --triple-candidates}의 커밋된
 * {@code promoted/cand-NN} 후보를 빌더 explore 진입부에서 재확인·소비한다. {@link TripleStore}와
 * 달리 이 클래스는 <b>읽기 전용 소비자</b>다 — promoted 후보를 다른 버킷으로 이동하지 않는다
 * (design spec §5.2 "빌더 explore는 그것을 소비만 한다").
 *
 * <p><b>재확인 시퀀스(호출자 {@code EndpointExplorationRunner} 책임 분담):</b>
 * <ol>
 *   <li>{@link #attempt}가 promoted 후보 존재 → T1({@link TripleValidator}) 재검증 →
 *       trial 1회 재확인({@link TrialRunner#runCandidate})까지 수행하고 {@link GateVerdict}를 반환한다.</li>
 *   <li>{@code ADOPTED}면 호출자가 후보의 seed/stub을 <b>영속적으로</b> 적용하고 캡처-on 확정 run을
 *       수행한다(REQ-018) — 확정 run이 실패하면 호출자가 원복하고 사유를 기록한다(REQ-019).</li>
 *   <li>{@code STALE}이면 T1 재검증 또는 trial 재확인이 실패한 것이다(REQ-020) — 호출자는 trial이
 *       건드렸을 수 있는 happy 시드 상태만 복원하고 원래 탐색 결과로 회귀한다.</li>
 *   <li>{@code NO_CANDIDATE}면 게이트가 비활성이거나 대기 promoted 후보가 없다 — 아무 것도 건드리지
 *       않는다(REQ-022 회귀 0).</li>
 * </ol>
 *
 * <p><b>필요 동반 아티팩트(이 task가 도입하는 관례 — REQ-036 e2e 배선의 확장 지점):</b> promoted
 * 후보의 T1 재검증에는 승격 당시의 {@code base/cand-NN}(마커-diff 불변 기준선)과
 * {@code <endpointId>/provenance-report.json}(seed.sql 화이트리스트의 DB_READ 테이블 판정 근거)이
 * 함께 커밋돼 있어야 한다. 둘 중 하나라도 없으면 안전 측 기본값으로 {@code STALE} 처리한다(재검증
 * 불가 상태를 조용히 통과시키지 않음 — TripleStore의 "검증 불가 상태를 승격으로 accept하지 않는다"와
 * 같은 규범).
 *
 * <p><b>단일 후보 정책(MVP):</b> promoted 버킷에 후보가 여럿이면 순번 최솟값(cand-01 우선) 1개만
 * 시도한다. 복수 promoted 후보 간 우선순위·폴백 순회는 REQ-018/019/020/035 수용기준이 명시하지
 * 않으므로 이 task 범위 밖으로 남긴다.
 */
public final class TriplePromotionGate {

    private static final Pattern CAND_DIR = Pattern.compile("cand-(\\d+)");

    public enum Kind { NO_CANDIDATE, STALE, ADOPTED }

    /** 채택된 후보의 재생 재료 — 호출자가 영속 적용(insertSeeds/stub 등록)에 사용. */
    public record CandidateMaterials(JsonNode body, List<SynthesizedInput.SeedRow> seedRows,
                                     ObjectNode stubMapping) {
    }

    /**
     * 게이트 판정. {@code relPath}는 STALE일 때만 {@code <endpointId>/promoted/cand-NN} 포맷으로
     * 채워진다(REQ-020/021 staleTriples 원소 포맷) — 이 포맷 계약은 코드리뷰 fix 이후에도 유지한다.
     *
     * <p><b>코드리뷰 Important 1/2 fix:</b> {@code digest}(nullable)와 {@code attachStubInapplicable}은
     * {@link TrialRunner#runCandidate}가 만든 attach 안전 게이트 사유(REQ-023 누락 플래그,
     * REQ-024 잔존 (table,pk), REQ-025 스텁 skip)를 호출자({@code EndpointExplorationRunner})까지
     * 실어 나르기 위한 필드다 — 이전에는 {@code reason} 문자열(상태 코드만 포함)로 요약되면서
     * digest 자체가 유실됐다. {@code digest}는 trial이 실제로 발화한 STALE에서만 채워지고(사전
     * 재검증 실패인 base/사본 없음·provenance-report 없음·T1 실패는 여전히 null — 이 경로들은 애초에
     * TrialRunner를 호출하지 않으므로 attach 관련 digest가 존재할 수 없다), {@code attachStubInapplicable}은
     * STALE/ADOPTED 어느 kind에서도(REQ-025는 승격 성공 여부와 무관하게 관측 가능해야 하므로) 채워질
     * 수 있다.
     */
    public record GateVerdict(Kind kind, Path candidateDir, String relPath,
                              CandidateMaterials materials, String reason,
                              FailureDigest digest, boolean attachStubInapplicable) {

        public static GateVerdict noCandidate() {
            return new GateVerdict(Kind.NO_CANDIDATE, null, null, null, null, null, false);
        }

        /** 사전 재검증 실패(TrialRunner 미호출) 경로 전용 — digest 없음, attachStubInapplicable=false. */
        public static GateVerdict stale(Path root, Path candDir, String reason) {
            return stale(root, candDir, reason, null, false);
        }

        /** trial이 실제로 발화한 STALE — {@code trialOutcome}의 digest/attachStubInapplicable을 그대로 싣는다. */
        public static GateVerdict stale(Path root, Path candDir, String reason,
                                        FailureDigest digest, boolean attachStubInapplicable) {
            String rel = root.relativize(candDir).toString().replace(java.io.File.separatorChar, '/');
            return new GateVerdict(Kind.STALE, candDir, rel, null, reason, digest, attachStubInapplicable);
        }

        public static GateVerdict adopted(Path candDir, CandidateMaterials materials,
                                          boolean attachStubInapplicable) {
            return new GateVerdict(Kind.ADOPTED, candDir, null, materials, null, null, attachStubInapplicable);
        }
    }

    private TriplePromotionGate() {
    }

    /**
     * promoted 후보 존재 → T1 재검증 → trial 1회 재확인까지 수행한다. {@code tripleCandidatesRoot}가
     * null이거나 endpoint 아래 promoted 후보가 없으면 즉시 {@code NO_CANDIDATE}(trial 미호출 —
     * DB/HTTP 부작용 없음).
     */
    public static GateVerdict attempt(Path tripleCandidatesRoot, Endpoint endpoint, List<TableSchema> tables,
                                      DbConfig.Type dialect, BodyShape shape, TrialRunner trialRunner,
                                      List<RequiredSeed> happySeeds) throws Exception {
        if (tripleCandidatesRoot == null) {
            return GateVerdict.noCandidate();
        }
        Path endpointDir = tripleCandidatesRoot.resolve(endpoint.id());
        List<Path> promoted = listCandDirs(endpointDir.resolve("promoted"));
        if (promoted.isEmpty()) {
            return GateVerdict.noCandidate();
        }
        Path candDir = promoted.get(0);

        Path baseDir = endpointDir.resolve("base").resolve(candDir.getFileName());
        if (!Files.isDirectory(baseDir)) {
            return GateVerdict.stale(tripleCandidatesRoot, candDir,
                    "base/ 사본 없음(T1 재검증 불가) — " + baseDir);
        }
        Path reportPath = endpointDir.resolve("provenance-report.json");
        if (!Files.exists(reportPath)) {
            return GateVerdict.stale(tripleCandidatesRoot, candDir,
                    "provenance-report.json 없음(T1 재검증 불가) — " + reportPath);
        }
        ProvenanceReport report = Json.mapper().readValue(reportPath.toFile(), ProvenanceReport.class);

        TripleValidator.ValidationResult validation =
                new TripleValidator(tables, dialect).validate(candDir, baseDir, report, shape);
        if (!validation.accepted()) {
            return GateVerdict.stale(tripleCandidatesRoot, candDir,
                    "T1 재검증 실패: " + validation.reasons());
        }

        TrialRunner.TrialOutcome trialOutcome = trialRunner.runCandidate(endpoint, candDir, happySeeds, report);
        if (!trialOutcome.promoted()) {
            return GateVerdict.stale(tripleCandidatesRoot, candDir, staleReason(trialOutcome),
                    trialOutcome.digest(), trialOutcome.attachStubInapplicable());
        }

        return GateVerdict.adopted(candDir, loadMaterials(candDir, dialect, tables), trialOutcome.attachStubInapplicable());
    }

    /**
     * 코드리뷰 Important 1 fix: attach 안전 게이트(REQ-023/024)가 만든 digest가 있으면 그 사유를
     * reason 문자열에도 반영한다(status만 담겨 digest가 유실되던 문제 수정) — log.warn이 이 reason을
     * 그대로 찍으므로, 사람이 읽는 로그에서도 즉시 원인을 알 수 있다. 일반 trial 실패(비-attach 또는
     * attach이지만 REQ-023/024와 무관한 통상 실패)는 기존 문구를 그대로 유지한다(회귀 0).
     */
    private static String staleReason(TrialRunner.TrialOutcome trialOutcome) {
        FailureDigest digest = trialOutcome.digest();
        if (digest != null && "ATTACH_SEED_GATE_CLOSED".equals(digest.outcomeKind())) {
            return "trial 재확인 실패(REQ-020, REQ-023 attach seed gate closed): " + digest.logExcerpt();
        }
        if (digest != null && "ATTACH_CLEANUP_BLOCKED".equals(digest.outcomeKind())) {
            return "trial 재확인 실패(REQ-020, REQ-024 attach cleanup blocked) — remaining row(s): "
                    + digest.attachRemainingRows();
        }
        return "trial 재확인 실패(REQ-020): status=" + trialOutcome.status();
    }

    private static List<Path> listCandDirs(Path bucketDir) throws IOException {
        if (!Files.isDirectory(bucketDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(bucketDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> CAND_DIR.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(TriplePromotionGate::seqOf))
                    .toList();
        }
    }

    private static int seqOf(Path candDir) {
        Matcher m = CAND_DIR.matcher(candDir.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static CandidateMaterials loadMaterials(Path candDir, DbConfig.Type dialect, List<TableSchema> tables) throws Exception {
        JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
        List<SynthesizedInput.SeedRow> seedRows = parseSeedRows(candDir.resolve("seed.sql"), dialect, tables);
        ObjectNode stubMapping = null;
        Path stubsFile = candDir.resolve("stubs.json");
        if (Files.exists(stubsFile)) {
            JsonNode node = Json.mapper().readTree(stubsFile.toFile());
            if (node instanceof ObjectNode on && !on.isEmpty()) {
                stubMapping = on;
            }
        }
        return new CandidateMaterials(body, seedRows, stubMapping);
    }

    /**
     * candidate {@code seed.sql}을 {@link SynthesizedInput.SeedRow} 목록으로 파싱한다 — 이미 T1
     * 화이트리스트(REQ-010)를 통과한 후보 전제이므로 {@link SeedSqlWhitelist#parseSingleInsert}로
     * 구조만 재확인하고 컬럼/리터럴을 그대로 옮긴다. 이렇게 하면 호출자가 기존
     * {@code EndpointExplorationRunner.insertSeeds}/{@code deleteSeeds}(dialect별 파라미터 바인딩,
     * IDENTITY 재동기화)를 변경 없이 재사용할 수 있다.
     */
    static List<SynthesizedInput.SeedRow> parseSeedRows(Path seedSqlFile, DbConfig.Type dialect,
                                                        List<TableSchema> tables) throws Exception {
        if (!Files.exists(seedSqlFile)) {
            return List.of();
        }
        String content = Files.readString(seedSqlFile);
        SeedSqlWhitelist parser = new SeedSqlWhitelist();
        List<SynthesizedInput.SeedRow> rows = new ArrayList<>();
        for (String line : SeedSqlWhitelist.nonBlankLines(content)) {
            List<String> ignoredReasons = new ArrayList<>();
            Optional<Insert> parsed = parser.parseSingleInsert(line, dialect, ignoredReasons);
            if (parsed.isEmpty()) {
                continue;   // 방어적 skip — T1 게이트를 통과한 후보 전제이므로 정상 경로에서는 발생하지 않음
            }
            Insert insert = parsed.get();
            String table = insert.getTable().getUnquotedName();
            List<Column> columns = insert.getColumns();
            List<? extends Expression> values =
                    insert.getValues() == null ? null : insert.getValues().getExpressions();
            if (columns == null || values == null || columns.size() != values.size()) {
                continue;
            }
            List<String> columnNames = new ArrayList<>();
            List<Object> literalValues = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                String columnName = columns.get(i).getUnquotedColumnName();
                columnNames.add(columnName);
                literalValues.add(coerceToColumnType(
                        literalToObject(values.get(i)), columnTypeOf(tables, table, columnName)));
            }
            rows.add(new SynthesizedInput.SeedRow(table, columnNames, literalValues));
        }
        return rows;
    }

    /** {@code tables}에서 (table, column)의 jdbcType(대문자). 스키마에 없으면 null. */
    private static String columnTypeOf(List<TableSchema> tables, String table, String column) {
        if (tables == null) {
            return null;
        }
        for (TableSchema schema : tables) {
            if (!schema.name().equalsIgnoreCase(table)) {
                continue;
            }
            for (io.graphrag.model.ColumnSchema c : schema.columns()) {
                if (c.name().equalsIgnoreCase(column)) {
                    return c.jdbcType() == null ? null : c.jdbcType().toUpperCase(java.util.Locale.ROOT);
                }
            }
        }
        return null;
    }

    /**
     * seed.sql 리터럴은 전부 문자열로 파싱되는데, 시간형 컬럼에 문자열을 그대로 바인딩하면 엄격한
     * 드라이버가 거부한다(Postgres: "column is of type timestamp but expression is of type character
     * varying"). 스키마를 알면 결정 가능한 변환이므로 여기서 temporal 타입으로 올린다. 변환할 수
     * 없는 표기는 원래 값을 그대로 둔다 — 후보를 조용히 바꾸지 않고 드라이버가 판단하게 한다.
     */
    private static Object coerceToColumnType(Object value, String jdbcType) {
        if (jdbcType == null || !(value instanceof String text) || text.isBlank()) {
            return value;
        }
        // java.time 타입을 그대로 바인딩한다(JDBC 4.2). java.sql.Timestamp/Time으로 내리면
        // Timestamp는 JVM 기본 타임존으로 해석되고 Time은 나노초를 절삭해, "조용한 값 변경 금지"
        // 원칙을 스스로 어긴다. 분기 순서도 중요하다 — TIMESTAMP/DATETIME이 문자열로 TIME/DATE를
        // 포함하므로 먼저 걸러야 한다.
        try {
            if (jdbcType.contains("TIMESTAMP") || jdbcType.contains("DATETIME")) {
                return text.contains(" ") || text.contains("T")
                        ? java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                        : java.time.LocalDate.parse(text).atStartOfDay();
            }
            if (jdbcType.contains("DATE")) {
                return java.time.LocalDate.parse(text);
            }
            if (jdbcType.contains("TIME")) {
                return java.time.LocalTime.parse(text);
            }
        } catch (RuntimeException e) {
            return value;   // 해석 불가한 표기 — 원본 유지(조용한 값 변경 금지)
        }
        return value;
    }

    /** JSqlParser 리터럴 표현식 → JDBC 바인딩용 Java 객체(String/Long/Double/null, 그 외 원문 문자열). */
    private static Object literalToObject(Expression expr) {
        if (expr instanceof SignedExpression signed) {
            Object inner = literalToObject(signed.getExpression());
            if (signed.getSign() == '-') {
                if (inner instanceof Long l) return -l;
                if (inner instanceof Double d) return -d;
            }
            return inner;
        }
        if (expr instanceof StringValue sv) return sv.getValue();
        if (expr instanceof LongValue lv) return lv.getValue();
        if (expr instanceof DoubleValue dv) return dv.getValue();
        if (expr instanceof NullValue) return null;
        return expr.toString();
    }
}
