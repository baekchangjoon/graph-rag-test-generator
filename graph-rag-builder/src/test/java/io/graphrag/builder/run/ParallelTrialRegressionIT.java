package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
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
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-017: trial 게이트 구간(T1 재검증→trial 재확인→확정 run)은 병렬 탐색과 겹치지 않게 endpoint
 * 단위로 직렬 실행돼야 한다. {@link EndpointExplorationRunner}는 이를 정적 락
 * ({@code TRIPLE_GATE_LOCK}, {@link EndpointExplorationRunner#tripleGateLockForTest()})으로
 * 구현한다 — 이 락은 병렬 fan-out의 모든 워커 스레드가 공유하므로, 인스턴스가 서로 달라도(별개
 * endpoint) 게이트 구간끼리는 절대 겹치지 않는다.
 *
 * <p>두 검증 축:
 * <ol>
 *   <li>{@link #req017_lockRejectsConcurrentHolders}: 락 자체의 상호배제(직접 락 사용).</li>
 *   <li>{@link #req017_twoEndpointsGateSectionsNeverOverlapUnderConcurrentAttempt}: 서로 다른
 *       endpoint 2개(하나는 ADOPT 성공, 하나는 STALE)를 동시에 시도해도 실제 게이트 구간(HTTP 왕복
 *       구간으로 관측)이 시간상 절대 겹치지 않고, 각자 기대한 판정(채택/stale)을 정확히 산출함을
 *       확인한다 — 병렬 parallelism&gt;1 구성에서의 산출 동일성(REQ-017 수용기준)을 endpoint-단위
     *   직접 게이트 호출로 재현한 회귀 스윕.</li>
 * </ol>
 */
class ParallelTrialRegressionIT {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    /** 각 HTTP 요청의 [startNanos, endNanos] 구간 — 게이트 구간 겹침 여부를 사후 검증한다. */
    private static final ConcurrentLinkedQueue<long[]> intervals = new ConcurrentLinkedQueue<>();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/a", exchange -> handle(exchange, () -> 200));   // 항상 성공(ADOPT)
        server.createContext("/api/b", exchange -> handle(exchange, () -> 500));   // 항상 실패(STALE)
        server.start();
    }

    @AfterAll
    static void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 80ms 인위 지연 후 응답 — 게이트 직렬화가 깨지면 두 endpoint의 구간이 겹치는 것으로 드러난다. */
    private static void handle(com.sun.net.httpserver.HttpExchange exchange,
                               java.util.function.IntSupplier status) throws IOException {
        long start = System.nanoTime();
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.getAsInt(), body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
        intervals.add(new long[] {start, System.nanoTime()});
    }

    @Test
    @DisplayName("REQ-017: TRIPLE_GATE_LOCK은 동시 보유를 거부한다(상호배제)")
    void req017_lockRejectsConcurrentHolders() throws Exception {
        ReentrantLock lock = EndpointExplorationRunner.tripleGateLockForTest();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObservedConcurrency = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable task = () -> {
            try {
                start.await();
                lock.lock();
                try {
                    int now = active.incrementAndGet();
                    maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, now));
                    Thread.sleep(50);
                    active.decrementAndGet();
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
        new Thread(task).start();
        new Thread(task).start();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).as("두 스레드 모두 타임아웃 없이 종료해야 한다").isTrue();

        assertThat(maxObservedConcurrency.get())
                .as("락 보유 구간 안에서는 동시 진입자가 항상 1명이어야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-017: 서로 다른 endpoint 2개를 동시 시도해도 게이트 구간이 겹치지 않고 각자 기대 판정을 산출한다")
    void req017_twoEndpointsGateSectionsNeverOverlapUnderConcurrentAttempt() throws Exception {
        intervals.clear();
        Path tripleRoot = tempDir.resolve("triples");

        seedCandidate(tripleRoot, "post-a", "INSERT INTO accounts_a (id, balance) VALUES ('ACC-A', 500);",
                "accounts_a");
        seedCandidate(tripleRoot, "post-b", "INSERT INTO accounts_b (id, balance) VALUES ('ACC-B', 500);",
                "accounts_b");

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<EndpointExplorationRunner.GateApplyResult> resultA = new AtomicReference<>();
        AtomicReference<EndpointExplorationRunner.GateApplyResult> resultB = new AtomicReference<>();
        AtomicReference<EndpointExplorationRunner> runnerA = new AtomicReference<>();
        AtomicReference<EndpointExplorationRunner> runnerB = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        Thread ta = new Thread(() -> runGate(start, "post-a", "/api/a", "accounts_a", tripleRoot,
                resultA, runnerA, failure));
        Thread tb = new Thread(() -> runGate(start, "post-b", "/api/b", "accounts_b", tripleRoot,
                resultB, runnerB, failure));
        ta.start();
        tb.start();
        start.countDown();
        ta.join(15_000);
        tb.join(15_000);

        assertThat(failure.get()).as("워커 스레드 예외 없어야 함").isNull();

        // endpoint A: 항상 200 → trial+확정 run 모두 성공 → ADOPTED.
        assertThat(runnerA.get().tripleAdoptedForTest()).as("A는 채택되어야 한다").isTrue();
        assertThat(runnerA.get().tripleStalePathsForTest()).isEmpty();
        // endpoint B: 항상 500 → trial 자체가 실패 → STALE.
        assertThat(runnerB.get().tripleAdoptedForTest()).as("B는 채택되지 않아야 한다").isFalse();
        assertThat(runnerB.get().tripleStalePathsForTest())
                .as("B는 trial 재확인 실패로 stale 보고되어야 한다")
                .containsExactly("post-b/promoted/cand-01");

        // REQ-017 핵심 단언: 두 endpoint의 실제 HTTP 왕복(게이트 구간) 시간창이 서로 절대 겹치지 않는다.
        List<long[]> sorted = intervals.stream()
                .sorted(java.util.Comparator.comparingLong(iv -> iv[0]))
                .toList();
        assertThat(sorted).as("A(1~2회)+B(1회) 최소 3건의 HTTP 왕복이 기록돼야 한다").hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i + 1 < sorted.size(); i++) {
            assertThat(sorted.get(i)[1])
                    .as("직렬화 위반 — 요청 %d 종료가 다음 요청 %d 시작보다 늦다(구간 겹침)", i, i + 1)
                    .isLessThanOrEqualTo(sorted.get(i + 1)[0]);
        }
    }

    private void runGate(CountDownLatch start, String endpointId, String path, String table,
                         Path tripleRoot, AtomicReference<EndpointExplorationRunner.GateApplyResult> resultRef,
                         AtomicReference<EndpointExplorationRunner> runnerRef, AtomicReference<Exception> failure) {
        try {
            start.await();
            try (Connection connection = newH2Connection(table)) {
                EndpointExplorationRunner runner = newRunner(connection, tripleRoot);
                runnerRef.set(runner);
                Endpoint endpoint = new Endpoint(endpointId, "POST", path, "T", "t", List.of(), false);
                SynthesizedInput priorHappy = new SynthesizedInput(Json.mapper().createObjectNode(), List.of());
                ExplorationOutcome priorOutcome = new ExplorationOutcome(List.of(), Set.of(), java.util.Map.of());
                StatusOnlyClassifier classifier = new StatusOnlyClassifier();
                ExplorationOrchestrator orchestrator =
                        new ExplorationOrchestrator(List.of(new HeuristicExplorer(classifier)), 1, classifier);
                EndpointExplorationRunner.GateApplyResult result = runner.applyTriplePromotionGate(
                        endpoint, List.of(), BodyShape.empty(), priorHappy, List.of(),
                        false, false, false, List.of(), java.util.Map.of(), java.util.Map.of(),
                        java.util.Map.of(), List.of(), List.of(), java.util.Map.of(), List.of(), List.of(),
                        orchestrator, priorOutcome);
                resultRef.set(result);
            }
        } catch (Exception e) {
            failure.set(e);
        }
    }

    private EndpointExplorationRunner newRunner(Connection connection, Path tripleCandidatesRoot) throws IOException {
        Path bootJar = tempDir.resolve("empty-boot-" + DB_SEQ.incrementAndGet() + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(bootJar))) {
            // 빈 zip — BranchCoverageAnalyzer가 여는 boot jar(분기 데이터는 이 테스트 관심사 밖).
        }
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

    private Connection newH2Connection(String table) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:parallel-trial-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE " + table + " (id VARCHAR(50) PRIMARY KEY, balance BIGINT)");
        }
        return connection;
    }

    private void seedCandidate(Path tripleRoot, String endpointId, String seedSql, String table)
            throws IOException {
        Path endpointDir = Files.createDirectories(tripleRoot.resolve(endpointId));
        String body = "{\"accountId\":\"ACC-1\"}";
        String stubs = "{}";
        for (String bucket : List.of("promoted", "base")) {
            Path candDir = Files.createDirectories(endpointDir.resolve(bucket).resolve("cand-01"));
            Files.writeString(candDir.resolve("body.json"), body);
            Files.writeString(candDir.resolve("seed.sql"), seedSql);
            Files.writeString(candDir.resolve("stubs.json"), stubs);
        }
        GuardFact guard = new GuardFact("Fixture.java:1", "EXISTS",
                List.of(new ValueRef(Origin.DB_READ, null, table, "id", null, null, "String", null, null)));
        ProvenanceReport report = new ProvenanceReport(endpointId, List.of(guard), List.of(), List.of());
        Files.writeString(endpointDir.resolve("provenance-report.json"), Json.mapper().writeValueAsString(report));
    }

    private static final class FakeCoverageProbe implements io.graphrag.builder.coverage.CoverageProbe {
        @Override public void baselineCut() { }
        @Override public org.jacoco.core.data.ExecutionDataStore requestDelta(String traceId) {
            return new org.jacoco.core.data.ExecutionDataStore();
        }
    }

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
