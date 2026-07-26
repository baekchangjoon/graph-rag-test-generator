package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.ExplorationOrchestrator;
import io.graphrag.builder.explore.ExplorationOutcome;
import io.graphrag.builder.explore.HeuristicExplorer;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.builder.provenance.ProvenanceReport;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.RequestHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-019/035: {@link EndpointExplorationRunner#applyTriplePromotionGate}(REQ-019)와
 * {@link BuilderCli#detectStaleEndpointTriples}(REQ-035)를 검증한다.
 *
 * <p>REQ-019는 {@code run()} 전체를 부팅하지 않고 게이트 본체를 직접 호출한다 — {@code doSend}(확정
 * run 경로)가 필요로 하는 {@code BranchCoverageAnalyzer}는 빈 zip을 boot jar로 사용해 실 SUT jar
 * 의존 없이 구성한다({@code TrialCaptureOffIT}·{@code TrialDigestIT}의 최소-의존 fake 패턴과 동일
 * 계열 — 여기서는 확정 run이 capture-on 경로를 타므로 그 두 fake만으로는 부족해 analyzer까지 추가한다).
 */
class TriplePromotionIT {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger callCount = new AtomicInteger();
    private static volatile java.util.function.IntSupplier statusSupplier;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/transfers", exchange -> {
            int n = callCount.incrementAndGet();
            int status = statusSupplier != null ? statusSupplier.getAsInt() : 200;
            byte[] body = ("{\"call\":" + n + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        callCount.set(0);
        statusSupplier = null;
    }

    private static SutHandle fakeSut() {
        return new SutHandle() {
            @Override public String baseUri() { return "http://localhost:" + port; }
            @Override public String readLog() { return ""; }
            @Override public long logOffset() { return 0; }
            @Override public String readLogFrom(long offset) { return ""; }
            @Override public String readLogRange(long start, long end) { return ""; }
            @Override public void stop() { }
        };
    }

    /** {@code doSend}(확정 run, capture-on)의 {@code analyzer.analyze()}가 여는 빈 boot jar. */
    private Path emptyBootJar() throws IOException {
        Path jar = tempDir.resolve("empty-boot-" + DB_SEQ.incrementAndGet() + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            // 의도적으로 엔트리 0개 — BranchCoverageAnalyzer는 BOOT-INF/classes/*.class만 찾으므로
            // 빈 zip이면 covered/missed가 항상 공집합(테스트 관심사 밖).
        }
        return jar;
    }

    private Connection newH2Connection() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:triple-promotion-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)");
        }
        return connection;
    }

    private static ProvenanceReport reportWithDbReadTable(String table) {
        GuardFact guard = new GuardFact("Fixture.java:1", "EXISTS",
                List.of(new ValueRef(Origin.DB_READ, null, table, "id", null, null, "String", null, null)));
        return new ProvenanceReport("post-api-transfers", List.of(guard), List.of(), List.of());
    }

    private void writeCandidate(Path dir, String body, String seed, String stubs) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("body.json"), body);
        Files.writeString(dir.resolve("seed.sql"), seed);
        Files.writeString(dir.resolve("stubs.json"), stubs);
    }

    private EndpointExplorationRunner newRunner(Connection connection, Path bootJar, Path tripleCandidatesRoot) {
        return new EndpointExplorationRunner(
                fakeSut(), connection, DbConfig.Type.POSTGRES,
                new FakeCoverageProbe(), new io.graphrag.builder.coverage.BranchCoverageAnalyzer(bootJar),
                0, /* httpCapture */ null,
                List.of(), List.of(),
                /* authProvider */ null, /* authConfig */ null,
                java.util.Map.of(), java.util.Map.of(),
                RequestHeaders.empty(), new FakeSqlCaptureBackend(), /* kafkaCapture */ null,
                new StatusOnlyClassifier(), List.of(), /* egressCollector */ null,
                java.util.Map.of(), /* traceParent */ null, /* errorContract */ null,
                tripleCandidatesRoot);
    }

    /** {@code doSend}(확정 run, capture-on)이 여는 {@code coverage.baselineCut/requestDelta}만 만족시키는 fake. */
    private static final class FakeCoverageProbe implements io.graphrag.builder.coverage.CoverageProbe {
        @Override public void baselineCut() { }
        @Override public org.jacoco.core.data.ExecutionDataStore requestDelta(String traceId) {
            return new org.jacoco.core.data.ExecutionDataStore();
        }
    }

    @Test
    @DisplayName("REQ-019: trial 재확인은 성공하지만 확정 run이 실패하면(비결정 의심) 후보를 폐기하고 원본 outcome/DB 상태로 회귀한다")
    void req019_confirmRunMismatchRejectsCandidateAndRestoresOriginal() throws Exception {
        Path tripleRoot = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String body = "{\"accountId\":\"ACC-1\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-1', 500);";
        String stubs = "{}";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), body, seed, stubs);
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), body, seed, stubs);
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        // 1번째 호출(trial, capture-off) → 200 성공. 2번째 이후(확정 run, capture-on) → 500 실패.
        // "trial은 통과하나 확정 run에서 결과가 달라지는 후보" 시나리오를 결정적으로 재현한다.
        statusSupplier = () -> callCount.get() == 1 ? 200 : 500;

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            EndpointExplorationRunner runner = newRunner(connection, emptyBootJar(), tripleRoot);

            EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(callCount.get()).as("trial 1회 + 확정 run 1회 = 2회 invoke").isEqualTo(2);

            // 폐기(REQ-019): 원본 outcome/happy를 그대로 반환(reference-equal — 아무 것도 재구성하지 않음).
            assertThat(result.outcome()).isSameAs(priorOutcome);
            assertThat(result.happy()).isSameAs(priorHappy);
            assertThat(runner.tripleAdoptedForTest()).isFalse();
            assertThat(runner.tripleTrialCountForTest()).isEqualTo(1);
            assertThat(runner.tripleStalePathsForTest()).as("REQ-019는 stale이 아니라 reject다").isEmpty();
            assertThat(runner.tripleRejectedReasonsForTest())
                    .as("비결정 의심 사유가 리포트에 남아야 한다")
                    .containsEntry("confirm-run-mismatch", 1);

            // DB 원복: 후보가 삽입한 행(ACC-1)이 정리되어 잔존하지 않아야 한다.
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
                rs.next();
                assertThat(rs.getInt(1)).as("확정 run 실패 후 후보 seed 행이 정리되어야 한다").isZero();
            }
        }
    }

    @Test
    @DisplayName("REQ-019 회귀: 재차 호출해도(다른 endpoint) 상태가 누적되지 않는다 — tripleTrialCount는 endpoint별 run() 단위")
    void req019_secondInvocationOnFreshRunnerStartsClean() throws Exception {
        // 별도 인스턴스는 관측 필드가 0/false/빈 상태에서 시작함을 재확인(run()의 초기화 관례와 일관).
        try (Connection connection = newH2Connection()) {
            EndpointExplorationRunner runner = newRunner(connection, emptyBootJar(), null);
            assertThat(runner.tripleTrialCountForTest()).isZero();
            assertThat(runner.tripleAdoptedForTest()).isFalse();
            assertThat(runner.tripleRejectedReasonsForTest()).isEmpty();
            assertThat(runner.tripleStalePathsForTest()).isEmpty();
        }
    }

    // ---- REQ-035 ----

    @Test
    @DisplayName("REQ-035: 인덱싱 결과에 없는 endpointId 아래 promoted 후보는 trial 없이 stale로 보고된다")
    void req035_unknownEndpointPromotedTripleReportedWithoutTrial() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req035");
        // "removed-endpoint"는 현재 인덱싱 결과에 없다(제거·개명 시나리오).
        Files.createDirectories(tripleRoot.resolve("removed-endpoint").resolve("promoted").resolve("cand-01"));
        Files.createDirectories(tripleRoot.resolve("removed-endpoint").resolve("promoted").resolve("cand-02"));
        // "known-endpoint"는 인덱싱 결과에 있다 — promoted가 있어도 stale 목록에 나타나면 안 된다.
        Files.createDirectories(tripleRoot.resolve("known-endpoint").resolve("promoted").resolve("cand-01"));
        // promoted가 없는 미지 endpoint 디렉토리는 조용히 skip(trial 대상 자체가 아님).
        Files.createDirectories(tripleRoot.resolve("no-promoted-endpoint").resolve("cand-01"));

        List<String> stale = BuilderCli.detectStaleEndpointTriples(
                tripleRoot, Set.of("known-endpoint"));

        assertThat(stale)
                .as("REQ-035 포맷: <endpointId>/promoted/cand-NN, trial 시도 없이 순수 파일시스템 스캔")
                .containsExactlyInAnyOrder(
                        "removed-endpoint/promoted/cand-01",
                        "removed-endpoint/promoted/cand-02");
    }

    @Test
    @DisplayName("REQ-035: --triple-candidates 루트가 없거나 endpointId가 모두 알려져 있으면 빈 목록")
    void req035_noTripleCandidatesRootOrAllKnownYieldsEmpty() throws Exception {
        assertThat(BuilderCli.detectStaleEndpointTriples(
                tempDir.resolve("does-not-exist"), Set.of())).isEmpty();

        Path tripleRoot = tempDir.resolve("triples-all-known");
        Files.createDirectories(tripleRoot.resolve("post-api-transfers").resolve("promoted").resolve("cand-01"));
        assertThat(BuilderCli.detectStaleEndpointTriples(
                tripleRoot, Set.of("post-api-transfers"))).isEmpty();
    }

    /** {@code doSend}의 {@code sqlCapture.begin()} 호출만 만족시키는 최소 fake — scope는 항상 비어 있다. */
    private static final class FakeSqlCaptureBackend
            implements io.graphrag.builder.capture.SqlCaptureBackend {
        @Override
        public Scope begin() {
            return new Scope() {
                @Override public java.util.Map<String, String> requestHeaders() { return java.util.Map.of(); }
                @Override public List<io.graphrag.builder.capture.ParsedSql> drain() { return List.of(); }
            };
        }
    }
}
