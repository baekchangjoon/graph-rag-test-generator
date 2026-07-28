package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.cli.BuilderCli;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.EndpointInvoker;
import io.graphrag.builder.explore.EndpointTarget;
import io.graphrag.builder.explore.ExplorationOrchestrator;
import io.graphrag.builder.explore.ExplorationOutcome;
import io.graphrag.builder.explore.HeuristicExplorer;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.builder.provenance.FailureDigest;
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
    /** 가장 최근 수신 요청의 raw body — REQ-018 리뷰 fix(stale target) 회귀 검증용. */
    private static volatile String lastRequestBody;
    /**
     * 코드리뷰 fix(REQ-024 관측성 테스트 전용) — 요청 수신 시 실행할 부작용. 실 SUT가 trial invoke
     * 도중 TrialRunner가 추적하지 못하는 행(FK 자식)을 만드는 상황을 재현하기 위한 훅.
     */
    private static volatile Runnable onRequestSideEffect;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/transfers", exchange -> {
            int n = callCount.incrementAndGet();
            if (onRequestSideEffect != null) {
                onRequestSideEffect.run();
            }
            int status = statusSupplier != null ? statusSupplier.getAsInt() : 200;
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
        lastRequestBody = null;
        onRequestSideEffect = null;
    }

    /** 로컬호스트의 확실히 닫힌 포트 — 연결 즉시 거부(ECONNREFUSED)를 유도해 확정 explore를 실패시킨다. */
    private static int closedPort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
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
            // REQ-024 관측성 테스트 전용 FK 자식 테이블 — TrialRunner가 추적하지 못하는 행이 남아
            // accounts 부모 행의 역-DELETE를 실패시키는 상황을 재현한다(다른 테스트는 참조 안 함).
            st.execute("CREATE TABLE transactions (id VARCHAR(50) PRIMARY KEY, account_id VARCHAR(50), "
                    + "FOREIGN KEY (account_id) REFERENCES accounts(id))");
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

    /**
     * 코드리뷰 fix 관측성 테스트 전용 — attach 안전 게이트(REQ-023/024/025)를 아는 25-arg 생성자로
     * runner를 만든다. {@code newRunner}와 동일하되 attachMode=true + 이중 opt-in 플래그를 명시한다.
     */
    private EndpointExplorationRunner newAttachRunner(Connection connection, Path bootJar,
                                                       Path tripleCandidatesRoot,
                                                       boolean allowSeed, boolean confirmNonProduction) {
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
                tripleCandidatesRoot, /* attachMode */ true, allowSeed, confirmNonProduction);
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

    @Test
    @DisplayName("REQ-018 Critical fix: ADOPT 후 target/invoker를 재생성해야 B2 재탐색이 채택된 body로 SUT를 재발행한다")
    void req018_rebuildTargetAfterAdoptSoSubsequentReexploreUsesAdoptedBody() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req018");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String candidateBody = "{\"accountId\":\"ACC-ADOPTED\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-ADOPTED', 500);";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), candidateBody, seed, "{}");
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), candidateBody, seed, "{}");
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        statusSupplier = () -> 200;   // trial + 확정 run 모두 성공 → ADOPT

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(
                Json.mapper().readTree("{\"accountId\":\"ORIGINAL-REJECTED\"}"), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            EndpointExplorationRunner runner = newRunner(connection, emptyBootJar(), tripleRoot);

            // run()이 게이트 호출 전에 이미 만들어 둔 "stale" invoker/target — 거부된 원본 body를 감싼다.
            EndpointInvoker staleInvoker = runner.buildInvokerForTest(endpoint, false, false, priorHappy);
            EndpointTarget staleTarget =
                    new EndpointTarget(endpoint, priorHappy.body(), List.of(), List.of(), staleInvoker);

            EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(runner.tripleAdoptedForTest()).as("trial+확정 run 모두 성공하므로 채택돼야 한다").isTrue();
            assertThat(result.happy()).as("happy가 candidate로 교체돼야 한다").isNotSameAs(priorHappy);

            EndpointExplorationRunner.TargetRebuildResult rebuilt = runner.rebuildTargetIfHappyChanged(
                    priorHappy, result.happy(), endpoint, false, false, List.of(), List.of(),
                    List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    staleInvoker, staleTarget);

            assertThat(rebuilt.target()).as("happy 참조가 바뀌었으므로 target도 재생성돼야 한다")
                    .isNotSameAs(staleTarget);
            assertThat(rebuilt.target().baseInput().get("accountId").asText())
                    .as("재생성된 target은 채택된 candidate body를 반영해야 한다")
                    .isEqualTo("ACC-ADOPTED");

            // 핵심 회귀: 재생성된 target으로 B2 루프와 동일하게 재explore하면 SUT가 채택된 body를 관측한다.
            lastRequestBody = null;
            ExplorationOutcome reExplored = orchestrator.explore(rebuilt.target());
            assertThat(reExplored.paths()).isNotEmpty();
            assertThat(lastRequestBody)
                    .as("재탐색이 재생성된 target을 썼다면 채택된 body(ACC-ADOPTED)가 재발행돼야 한다")
                    .contains("ACC-ADOPTED");

            // 대조군: 재생성 없이 stale target을 그대로 썼다면(수정 전 버그) 거부된 원본 body가 재발행된다.
            lastRequestBody = null;
            orchestrator.explore(staleTarget);
            assertThat(lastRequestBody)
                    .as("대조군 — stale target은 여전히 원본(거부된) body를 보낸다: 이것이 수정 전 버그의 실제 증상이다")
                    .contains("ORIGINAL-REJECTED");

            // no-op 분기: happy가 안 바뀌면(NO_CANDIDATE/STALE) 재생성 없이 기존 참조를 그대로 반환한다.
            EndpointExplorationRunner.TargetRebuildResult unchanged = runner.rebuildTargetIfHappyChanged(
                    priorHappy, priorHappy, endpoint, false, false, List.of(), List.of(),
                    List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    staleInvoker, staleTarget);
            assertThat(unchanged.target()).isSameAs(staleTarget);
            assertThat(unchanged.invoker()).isSameAs(staleInvoker);
        }
    }

    @Test
    @DisplayName("REQ-019 Important fix: 확정 explore 도중 예외가 나면 cumulativeCoverage가 게이트 이전 상태로 원복된다")
    void req019_exceptionDuringConfirmExploreRestoresCumulativeCoverage() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req019-cov");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String body = "{\"accountId\":\"ACC-1\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-1', 500);";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), body, seed, "{}");
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), body, seed, "{}");
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        // trial(1번째 HTTP 시도)은 정상 포트로 보내 성공시키고, 확정 run(2번째 시도)만 닫힌 포트로 보내
        // 연결 거부(예외)를 유발한다 — "확정 explore 시작 후(coverage 리셋 이후) 예외" 경로를 결정적으로 재현.
        int deadPort = closedPort();
        AtomicInteger baseUriCalls = new AtomicInteger();
        SutHandle flakySut = new SutHandle() {
            @Override public String baseUri() {
                return baseUriCalls.incrementAndGet() == 1
                        ? "http://localhost:" + port : "http://localhost:" + deadPort;
            }
            @Override public String readLog() { return ""; }
            @Override public long logOffset() { return 0; }
            @Override public String readLogFrom(long offset) { return ""; }
            @Override public String readLogRange(long start, long end) { return ""; }
            @Override public void stop() { }
        };
        statusSupplier = () -> 200;   // trial(정상 포트 경유)은 성공 판정

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            EndpointExplorationRunner runner = new EndpointExplorationRunner(
                    flakySut, connection, DbConfig.Type.POSTGRES,
                    new FakeCoverageProbe(), new io.graphrag.builder.coverage.BranchCoverageAnalyzer(emptyBootJar()),
                    0, /* httpCapture */ null, List.of(), List.of(),
                    /* authProvider */ null, /* authConfig */ null,
                    java.util.Map.of(), java.util.Map.of(),
                    RequestHeaders.empty(), new FakeSqlCaptureBackend(), /* kafkaCapture */ null,
                    new StatusOnlyClassifier(), List.of(), /* egressCollector */ null,
                    java.util.Map.of(), /* traceParent */ null, /* errorContract */ null, tripleRoot);

            org.jacoco.core.data.ExecutionDataStore marker = new org.jacoco.core.data.ExecutionDataStore();
            runner.setCumulativeCoverageForTest(marker);

            EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(result.outcome()).as("확정 explore 예외 시 원본 outcome으로 회귀해야 한다").isSameAs(priorOutcome);
            assertThat(runner.tripleAdoptedForTest()).isFalse();
            assertThat(runner.tripleRejectedReasonsForTest())
                    .as("확정 run 진입 전 단계(insertSeeds/coverage.baselineCut 이후) 예외는 adoption-error로 기록된다")
                    .containsEntry("adoption-error", 1);
            assertThat(runner.cumulativeCoverageForTest())
                    .as("확정 explore 도중 예외가 나도 cumulativeCoverage는 게이트 이전 마커로 원복돼야 한다"
                            + "(수정 전에는 catch 블록에서 priorCumulative가 스코프 밖이라 빈 상태로 남았다)")
                    .isSameAs(marker);
        }
    }

    // ---- 코드리뷰 fix: attach 게이트 사유가 관측 가능한 산출물까지 도달하는지(REQ-023/024/025) ----

    @Test
    @DisplayName("REQ-023 리뷰 fix: attach seed gate 사유가 tripleRejected 카운터와 gate-digest.json 파일로 관측 가능하다")
    void req023_attachSeedGateReasonReachesTripleRejectedAndDigestFile() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req023-observability");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String body = "{\"accountId\":\"ACC-GATE\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-GATE', 500);";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), body, seed, "{}");
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), body, seed, "{}");
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            // attach 모드이지만 두 플래그 모두 false → REQ-023 이중 opt-in 미충족(0개).
            EndpointExplorationRunner runner = newAttachRunner(connection, emptyBootJar(), tripleRoot, false, false);

            EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(result.outcome()).isSameAs(priorOutcome);
            assertThat(callCount.get()).as("seed gate가 닫혀 있으면 invoke가 전혀 발생하지 않아야 한다").isZero();
            assertThat(runner.tripleStalePathsForTest())
                    .as("REQ-020/021 staleTriples 포맷은 그대로 유지된다")
                    .containsExactly("post-api-transfers/promoted/cand-01");
            assertThat(runner.tripleRejectedReasonsForTest())
                    .as("REQ-023 사유가 tripleRejected 카운터로 분류돼야 한다")
                    .containsEntry("attach-seed-gate-closed", 1);

            Path digestFile = tripleRoot.resolve("post-api-transfers").resolve("gate-digest.json");
            assertThat(digestFile).as("사유 상세(누락 플래그)가 파일로 관측 가능해야 한다").exists();
            FailureDigest digest = Json.mapper().readValue(digestFile.toFile(), FailureDigest.class);
            assertThat(digest.outcomeKind()).isEqualTo("ATTACH_SEED_GATE_CLOSED");
            assertThat(digest.logExcerpt())
                    .as("누락된 두 플래그가 모두 사유에 지목돼야 한다")
                    .contains("--attach-allow-seed")
                    .contains("--confirm-non-production");
        }
    }

    @Test
    @DisplayName("REQ-024 리뷰 fix: attach 역-DELETE 실패 사유(잔존 table.pk)가 tripleRejected 카운터와 gate-digest.json 파일로 관측 가능하다")
    void req024_attachCleanupBlockedReasonReachesTripleRejectedAndDigestFile() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req024-observability");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String body = "{\"accountId\":\"ACC-CLEANUP\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-CLEANUP', 500);";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), body, seed, "{}");
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), body, seed, "{}");
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        statusSupplier = () -> 200;   // trial invoke 자체는 성공 — 이후 cleanup 실패로 승격이 차단되는 시나리오.

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            // invoke가 TrialRunner 미추적 FK 자식 행을 흉내낸다(실 SUT의 부작용을 대신 재현) — accounts
            // 행의 역-DELETE가 FK 위반으로 실패하도록 만든다.
            onRequestSideEffect = () -> {
                try (Statement st = connection.createStatement()) {
                    st.execute("INSERT INTO transactions (id, account_id) VALUES ('txn-req024', 'ACC-CLEANUP')");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            // 이중 opt-in 충족(2개) → seed는 정상 적용되지만 cleanup이 실패하는 시나리오.
            EndpointExplorationRunner runner = newAttachRunner(connection, emptyBootJar(), tripleRoot, true, true);

            EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(result.outcome()).isSameAs(priorOutcome);
            assertThat(callCount.get())
                    .as("trial invoke 1회만 발생 — cleanup 실패는 승격만 막을 뿐 확정 run으로 이어지지 않는다")
                    .isEqualTo(1);
            assertThat(runner.tripleRejectedReasonsForTest())
                    .as("REQ-024 사유가 tripleRejected 카운터로 분류돼야 한다")
                    .containsEntry("attach-cleanup-blocked", 1);

            Path digestFile = tripleRoot.resolve("post-api-transfers").resolve("gate-digest.json");
            assertThat(digestFile).exists();
            FailureDigest digest = Json.mapper().readValue(digestFile.toFile(), FailureDigest.class);
            assertThat(digest.outcomeKind()).isEqualTo("ATTACH_CLEANUP_BLOCKED");
            assertThat(digest.attachRemainingRows())
                    // C4 fix 이후 식별자는 후보 텍스트가 아니라 DB 카탈로그 표기를 쓴다(H2는 대문자로
                    // 보고) — 테이블/컬럼은 대소문자 무시로, PK 값은 그대로 대조한다.
                    .as("잔존 (table,pk) 리포트가 파일 필드로 관측 가능해야 한다")
                    .anyMatch(row -> row.toLowerCase(java.util.Locale.ROOT).contains("accounts")
                            && row.contains("ACC-CLEANUP"));

            // teardown: 이 테스트가 남긴 FK 자식 행을 먼저 지운 뒤 부모 행을 정리한다(H2 in-memory,
            // 다른 테스트와 격리된 DB이지만 상태를 명시적으로 되돌린다).
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM transactions WHERE id = 'txn-req024'");
                st.execute("DELETE FROM accounts WHERE id = 'ACC-CLEANUP'");
            }
        }
    }

    @Test
    @DisplayName("REQ-025 리뷰 fix: attach 스텁 skip(EXTERNAL_RESPONSE inapplicable)이 승격 성공 여부와 무관하게 tripleRejected 카운터로 관측 가능하다")
    void req025_attachStubInapplicableReachesTripleRejectedRegardlessOfPromotion() throws Exception {
        Path tripleRoot = tempDir.resolve("triples-req025-observability");
        Path endpointDir = Files.createDirectories(tripleRoot.resolve("post-api-transfers"));
        String candidateBody = "{\"accountId\":\"ACC-STUB\"}";
        String seed = "INSERT INTO accounts (id, balance) VALUES ('ACC-STUB', 500);";
        // 비어있지 않은 stub — 비-attach였다면 등록됐을 EXTERNAL_RESPONSE 스텁.
        String nonEmptyStub = "{\"request\":{\"method\":\"POST\",\"urlPath\":\"/fraud/check\"},"
                + "\"response\":{\"status\":200,\"jsonBody\":{\"status\":\"CLEAR\"}}}";
        writeCandidate(endpointDir.resolve("promoted").resolve("cand-01"), candidateBody, seed, nonEmptyStub);
        writeCandidate(endpointDir.resolve("base").resolve("cand-01"), candidateBody, seed, nonEmptyStub);
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(reportWithDbReadTable("accounts")));

        statusSupplier = () -> 200;   // trial + 확정 run 모두 성공 → ADOPT(승격 성공 경로에서도 관측돼야 함).

        Endpoint endpoint = new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);
        SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
        ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
        StatusOnlyClassifier classifier = new StatusOnlyClassifier();
        ExplorationOrchestrator orchestrator =
                new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);

        try (Connection connection = newH2Connection()) {
            EndpointExplorationRunner runner = newAttachRunner(connection, emptyBootJar(), tripleRoot, true, true);

            runner.applyTriplePromotionGate(
                    endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                    false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                    orchestrator, priorOutcome);

            assertThat(runner.tripleAdoptedForTest())
                    .as("stub inapplicable과 무관하게 trial+확정 run이 모두 성공하면 채택돼야 한다")
                    .isTrue();
            assertThat(runner.tripleRejectedReasonsForTest())
                    .as("REQ-025 사유(스텁 skip)가 승격 성공(ADOPTED) 경로에서도 관측 가능해야 한다")
                    .containsEntry("attach-stub-inapplicable", 1);
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

    @Test
    @DisplayName("REQ-018: 삼중 후보가 채택되면 SQL-hint pass-2를 돌리지 않는다(채택된 입력 덮어쓰기 방지)")
    void req018_sqlHintPass2SkippedAfterAdoption() {
        // pass-2는 입력을 ReadInputSynthesizer로 다시 합성하므로 채택된 body/seed를 probe 값으로
        // 덮어쓴다. 실측(mindgraph): 채택 직후 pass-2가 nodes_json='probe'로 재시드해 확정 run의
        // 200이 최종 리포트에서 404로 되돌아갔다.
        assertThat(EndpointExplorationRunner.shouldRunSqlHintPass2(true, false, true))
                .as("채택 후에는 pass-2를 돌리면 안 된다")
                .isFalse();
        assertThat(EndpointExplorationRunner.shouldRunSqlHintPass2(true, false, false))
                .as("채택이 없으면 종전대로 pass-2를 돌린다(회귀 0)")
                .isTrue();
        assertThat(EndpointExplorationRunner.shouldRunSqlHintPass2(false, false, false))
                .as("read-path 시드 대상이 아니면 종전대로 돌리지 않는다")
                .isFalse();
    }

}
