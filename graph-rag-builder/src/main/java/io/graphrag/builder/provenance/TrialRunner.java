package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.RequiredSeed;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.insert.Insert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T2 trial 실행기(REQ-013/014/016). 후보 1개를 실 SUT에 적용해 판정하고, 실패 시
 * {@link FailureDigest}를 산출한다. 시퀀스(REQ-013):
 * <ol>
 *   <li>① 기존 happy 시드 정리 — 현행 {@code EndpointExplorationRunner.resetSeeds}와 동일한
 *       reverse-DELETE 경로만 재사용한다(재삽입은 하지 않음 — candidate의 {@code seed.sql}이 그
 *       자리를 대체한다).</li>
 *   <li>② 후보 {@code seed.sql} INSERT — 삽입한 (table, pk 컬럼, pk 리터럴)을 추적해 finally에서
 *       역순 정리한다.</li>
 *   <li>③ 후보 {@code stubs.json} 등록 — 빈 객체({@code {}}, TripleSynthesizer의 "stub 없음" 관례)면
 *       skip. {@link #httpCapture}가 null(외부 stub 서버 미연결)이어도 skip.</li>
 *   <li>④ 후보 {@code body.json}으로 캡처-off invoke({@link TrialInvoker}, 실제로는
 *       {@code EndpointExplorationRunner.invokeTrial} 메서드 참조) 후
 *       {@link ResponseClassifier#classify}로 판정.</li>
 * </ol>
 *
 * <p>trial은 확정 run이 아니라 probe다 — 판정과 무관하게 이 후보가 삽입한 행은 finally에서 항상
 * 정리한다(다음 후보 시도가 DB 잔여 상태와 겹치지 않게). 승격(promoted/로 이동) 자체는 이 클래스의
 * 책임이 아니다 — {@link #runCandidate}는 판정 결과만 반환하고, 실제 {@link TripleStore#promote}/
 * {@link TripleStore#fail} 호출은 호출자(trial CLI 루프)가 예산 로직과 함께 수행한다.
 */
public final class TrialRunner {

    private static final Logger log = LoggerFactory.getLogger(TrialRunner.class);

    /** REQ-015 캡처-off invoke 진입점. 운영 배선은 {@code EndpointExplorationRunner::invokeTrial}. */
    @FunctionalInterface
    public interface TrialInvoker {
        InvocationOutcome invoke(Endpoint endpoint, JsonNode body) throws Exception;
    }

    /** 후보 1개의 trial 결과. {@code promoted=true}면 digest는 null. */
    public record TrialOutcome(boolean promoted, int status, FailureDigest digest) {
    }

    private record InsertedRow(String table, String pkColumn, String pkLiteralSql) {
    }

    private final Connection connection;
    private final DbConfig.Type dbType;
    private final HttpCaptureServer httpCapture;   // nullable — null이면 stub 등록(③) skip
    private final ResponseClassifier classifier;
    private final SutHandle sut;                   // 로그 슬라이스(REQ-014 stackExcerpt)
    private final TrialInvoker invoker;

    public TrialRunner(Connection connection, DbConfig.Type dbType, HttpCaptureServer httpCapture,
                       ResponseClassifier classifier, SutHandle sut, TrialInvoker invoker) {
        this.connection = connection;
        this.dbType = dbType;
        this.httpCapture = httpCapture;
        this.classifier = classifier;
        this.sut = sut;
        this.invoker = invoker;
    }

    public TrialOutcome runCandidate(Endpoint endpoint, Path candDir, List<RequiredSeed> happySeeds,
                                     ProvenanceReport report) throws Exception {
        resetHappySeeds(happySeeds);
        List<InsertedRow> inserted = insertCandidateSeed(candDir.resolve("seed.sql"));
        UUID stubId = registerCandidateStub(candDir.resolve("stubs.json"));
        try {
            JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
            long logStart = sut.logOffset();
            InvocationOutcome invocation = invoker.invoke(endpoint, body);
            Outcome classified = classifier.classify(invocation.status(), invocation.response());
            if (classified.kind() == Outcome.Kind.SUCCESS) {
                return new TrialOutcome(true, invocation.status(), null);
            }
            long logEnd = sut.logOffset();
            String logExcerpt = sut.readLogRange(logStart, logEnd);
            String stackExcerpt = extractStackFrames(logExcerpt);
            FailureDigest digest = FailureDigest.of(invocation.status(), classified.kind(), body,
                    invocation.response(), logExcerpt, stackExcerpt, report);
            return new TrialOutcome(false, invocation.status(), digest);
        } finally {
            if (stubId != null) {
                httpCapture.removeStub(stubId);
            }
            deleteInsertedRows(inserted);
        }
    }

    /** ① happy 시드 정리 — reverse-order(child→parent) DELETE만(재삽입 없음, resetSeeds와 구분). */
    private void resetHappySeeds(List<RequiredSeed> happySeeds) {
        if (happySeeds == null) {
            return;
        }
        for (int i = happySeeds.size() - 1; i >= 0; i--) {
            RequiredSeed seed = happySeeds.get(i);
            if (seed.columns().isEmpty() || seed.values().isEmpty()) {
                continue;
            }
            String sql = "DELETE FROM " + seed.table() + " WHERE " + seed.columns().get(0) + " = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, seed.values().get(0));
                ps.executeUpdate();
            } catch (Exception e) {
                log.warn("trial happy-seed reset delete failed: {}.{}", seed.table(), seed.columns().get(0), e);
            }
        }
    }

    /**
     * ② 후보 {@code seed.sql}을 줄 단위로 실행하고 삽입한 (table, pk컬럼, pk 리터럴)을 추적한다. 이
     * 시점의 seed.sql은 이미 T1 게이트({@link TripleValidator}, allowlist·마커-diff)를 통과했다는
     * 전제이므로 파싱된 원문을 그대로 실행한다.
     */
    private List<InsertedRow> insertCandidateSeed(Path seedSqlFile) throws Exception {
        List<InsertedRow> rows = new ArrayList<>();
        if (!Files.exists(seedSqlFile)) {
            return rows;
        }
        String content = Files.readString(seedSqlFile);
        SeedSqlWhitelist parser = new SeedSqlWhitelist();
        for (String line : SeedSqlWhitelist.nonBlankLines(content)) {
            List<String> ignoredReasons = new ArrayList<>();
            Optional<Insert> parsed = parser.parseSingleInsert(line, dbType, ignoredReasons);
            if (parsed.isEmpty()) {
                continue;   // 방어적 skip — T1 게이트를 통과한 후보 전제이므로 정상 경로에서는 발생하지 않음
            }
            try (Statement st = connection.createStatement()) {
                st.execute(line);
            }
            Insert insert = parsed.get();
            String table = insert.getTable().getUnquotedName();
            List<Column> columns = insert.getColumns();
            if (columns != null && !columns.isEmpty() && insert.getValues() != null
                    && !insert.getValues().getExpressions().isEmpty()) {
                Expression firstValue = insert.getValues().getExpressions().get(0);
                rows.add(new InsertedRow(table, columns.get(0).getUnquotedColumnName(), firstValue.toString()));
            }
        }
        return rows;
    }

    /** 후보가 삽입한 행을 역순으로 삭제한다(다음 trial 시도와 상태가 겹치지 않도록). best-effort. */
    private void deleteInsertedRows(List<InsertedRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            InsertedRow r = rows.get(i);
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM " + r.table() + " WHERE " + r.pkColumn() + " = " + r.pkLiteralSql());
            } catch (Exception e) {
                log.warn("trial candidate seed cleanup failed: {}.{}={}",
                        r.table(), r.pkColumn(), r.pkLiteralSql(), e);
            }
        }
    }

    /**
     * ③ 후보 {@code stubs.json}을 등록한다. httpCapture가 없거나(외부 stub 서버 미연결) 파일이 없거나
     * 빈 객체({@code {}}, TripleSynthesizer/{@code stubsJsonContent} "stub 없음" 관례)면 skip(null
     * 반환). 빈 객체 판정은 파싱된 {@link JsonNode#isEmpty()}로 한다 — {@code writerWithDefaultPrettyPrinter}가
     * 실제로는 {@code "{ }"}(공백 포함)를 산출하므로 원문 문자열 정확 일치({@code "{}"})는 오탐(실제
     * 빈 stub을 "내용 있음"으로 오판)을 낸다.
     */
    private UUID registerCandidateStub(Path stubsJsonFile) throws Exception {
        if (httpCapture == null || !Files.exists(stubsJsonFile)) {
            return null;
        }
        String content = Files.readString(stubsJsonFile);
        if (content.isBlank()) {
            return null;
        }
        JsonNode node = Json.mapper().readTree(content);
        if (node == null || node.isEmpty()) {
            return null;
        }
        StubMapping mapping = StubMapping.buildFrom(content);
        httpCapture.registerStub(mapping);
        return mapping.getId();
    }

    /** 로그 구간에서 {@code "at "}로 시작하는 스택 프레임 줄만 추출(REQ-014 stackExcerpt). 없으면 빈 문자열. */
    static String extractStackFrames(String logExcerpt) {
        if (logExcerpt == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : logExcerpt.split("\\R")) {
            if (line.strip().startsWith("at ")) {
                if (!sb.isEmpty()) {
                    sb.append(System.lineSeparator());
                }
                sb.append(line.strip());
            }
        }
        return sb.toString();
    }
}
