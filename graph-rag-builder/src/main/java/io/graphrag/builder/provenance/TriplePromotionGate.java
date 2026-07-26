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
     * 채워진다(REQ-020/021 staleTriples 원소 포맷).
     */
    public record GateVerdict(Kind kind, Path candidateDir, String relPath,
                              CandidateMaterials materials, String reason) {

        public static GateVerdict noCandidate() {
            return new GateVerdict(Kind.NO_CANDIDATE, null, null, null, null);
        }

        public static GateVerdict stale(Path root, Path candDir, String reason) {
            String rel = root.relativize(candDir).toString().replace(java.io.File.separatorChar, '/');
            return new GateVerdict(Kind.STALE, candDir, rel, null, reason);
        }

        public static GateVerdict adopted(Path candDir, CandidateMaterials materials) {
            return new GateVerdict(Kind.ADOPTED, candDir, null, materials, null);
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
            return GateVerdict.stale(tripleCandidatesRoot, candDir,
                    "trial 재확인 실패(REQ-020): status=" + trialOutcome.status());
        }

        return GateVerdict.adopted(candDir, loadMaterials(candDir, dialect));
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

    private static CandidateMaterials loadMaterials(Path candDir, DbConfig.Type dialect) throws Exception {
        JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
        List<SynthesizedInput.SeedRow> seedRows = parseSeedRows(candDir.resolve("seed.sql"), dialect);
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
    static List<SynthesizedInput.SeedRow> parseSeedRows(Path seedSqlFile, DbConfig.Type dialect) throws Exception {
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
                columnNames.add(columns.get(i).getUnquotedColumnName());
                literalValues.add(literalToObject(values.get(i)));
            }
            rows.add(new SynthesizedInput.SeedRow(table, columnNames, literalValues));
        }
        return rows;
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
