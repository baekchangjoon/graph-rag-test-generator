package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * PoC gate V4: 분산 트레이스 귀속 — 단일 JVM(REQ-006) + 멀티 JVM(REQ-007).
 *
 * <p>동일 traceId가 diary in-process coverage(단일 JVM, REQ-006)와
 * mindgraph Kafka consumer coverage(별도 JVM, REQ-007) 양쪽에 귀속됨을 검증한다.
 *
 * <p>사전 조건: {@code e2e/poc-fanout/v4-distributed-attribution.sh}를 먼저 실행해
 * tainted-spring compose 서비스가 기동되고 traceId에 대한 flush가 완료된 상태여야 한다.
 * 혹은 이 테스트를 직접 실행하면 compose 기동→요청→flush를 자동 수행한다.
 *
 * <p>실행:
 * <pre>
 *   POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V4DistributedAttributionPoc*'
 * </pre>
 *
 * <p>환경 변수:
 * <ul>
 *   <li>{@code POC_FANOUT_E2E=1} — 이 테스트 활성화 (필수)</li>
 *   <li>{@code TAINTED_PLATFORM} — tainted-spring-platform 경로
 *       (기본: {@code ~/github_tainted-spring/tainted-spring-platform})</li>
 *   <li>{@code V4_TRACE_ID} — 32-hex traceId (기본: {@code v4poc0000000000000000000000000001})</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V4DistributedAttributionPoc {

    // Valid 32-hex traceId for W3C traceparent. "v4poc..." is not hex and would be rejected by OTel.
    private static final String DEFAULT_TRACE_ID    = "76340000000000000000000000000001";
    private static final int    DIARY_PORT           = 8082;
    private static final int    DIARY_CTL_PORT       = 6310;
    private static final int    MINDGRAPH_CTL_PORT   = 6311;
    private static final int    KAFKA_WAIT_MS        = 8_000;
    private static final int    EXEC_AWAIT_MS        = 15_000;

    @Test
    @DisplayName("REQ-006: diary in-process covered probes > 0 (단일 JVM attribution)")
    void diaryInProcessAttribution() throws Exception {
        String traceId = traceId();
        Path diaryExec = diaryExecPath(traceId);

        runV4Flow(traceId, diaryExec, mindgraphExecPath(traceId));

        ExecutionDataStore diaryStore = load(diaryExec);
        long diaryProbes = countCoveredProbes(diaryStore);

        System.out.printf("[V4-REQ006] diary exec=%s%n", diaryExec);
        System.out.printf("[V4-REQ006] diary covered probes = %d%n", diaryProbes);
        printClassSummary(diaryStore, "diary");

        assertThat(diaryProbes)
                .as("REQ-006: diary in-process covered probes must be > 0 (단일 JVM attribution)")
                .isGreaterThan(0L);

        System.out.println("[V4-REQ006] PASS — diary in-process probes=" + diaryProbes);
    }

    @Test
    @DisplayName("REQ-006/REQ-007: 동일 traceId가 diary(단일 JVM)와 mindgraph consumer(멀티 JVM) 양쪽에 귀속")
    void consumerCoverageAttributedToSameTraceId() throws Exception {
        String traceId = traceId();
        Path diaryExec     = diaryExecPath(traceId);
        Path mindgraphExec = mindgraphExecPath(traceId);

        runV4Flow(traceId, diaryExec, mindgraphExec);

        // ── REQ-006: diary in-process ────────────────────────────────────────
        ExecutionDataStore diaryStore = load(diaryExec);
        long diaryProbes = countCoveredProbes(diaryStore);
        System.out.printf("[V4-REQ006] diary covered probes = %d%n", diaryProbes);
        printClassSummary(diaryStore, "diary");

        // ── REQ-007: mindgraph Kafka consumer (별도 JVM) ─────────────────────
        ExecutionDataStore mindgraphStore = load(mindgraphExec);
        long mindgraphProbes = countCoveredProbes(mindgraphStore);
        long consumerProbes  = countConsumerProbes(mindgraphStore);
        System.out.printf("[V4-REQ007] mindgraph total covered probes = %d%n", mindgraphProbes);
        System.out.printf("[V4-REQ007] DiaryCreatedConsumer+GraphService+Extractor probes = %d%n", consumerProbes);
        printClassSummary(mindgraphStore, "mindgraph");

        // ── 판정 ──────────────────────────────────────────────────────────────
        boolean req006Pass = diaryProbes > 0;
        boolean req007Pass = mindgraphProbes > 0;    // cross-JVM attribution non-empty
        boolean consumerPass = consumerProbes > 0;  // HARD: actual consumer classes hit

        System.out.printf("[V4] REQ-006 diary=%d %s%n",      diaryProbes,    req006Pass ? "PASS" : "FAIL");
        System.out.printf("[V4] REQ-007 mindgraph=%d %s%n",  mindgraphProbes, req007Pass ? "PASS" : "FAIL");
        System.out.printf("[V4] consumer-class probes=%d %s%n", consumerProbes, consumerPass ? "PASS" : "FAIL");

        if (!req006Pass) {
            fail("REQ-006 FAIL: diary in-process covered probes = 0. "
                    + "diary exec=" + diaryExec + " (size=" + execSize(diaryExec) + " bytes). "
                    + "Check compose logs: diary [pjacoco] agent installed?");
        }
        if (!req007Pass) {
            fail("REQ-007 FAIL: mindgraph (별도 JVM) covered probes = 0. "
                    + "mindgraph exec=" + mindgraphExec + " (size=" + execSize(mindgraphExec) + " bytes). "
                    + "Distributed cross-JVM attribution NOT working. Strategy A is BLOCKED. "
                    + "Record in spec §11 as V4 FAIL and stop PoC.");
        }
        if (!consumerPass) {
            fail("REQ-007 FAIL (consumer-class): mindgraph total probes=" + mindgraphProbes
                    + " but DiaryCreatedConsumer+GraphService+RuleBasedGraphExtractor probe count=0. "
                    + "Cross-JVM consumer-class coverage NOT attributed — the core REQ-007 claim "
                    + "(CROSS-JVM CONSUMER coverage) is not met even if other mindgraph classes appear. "
                    + "mindgraph exec=" + mindgraphExec + " (size=" + execSize(mindgraphExec) + " bytes).");
        }

        System.out.println("[V4] PASS — REQ-006 diary=" + diaryProbes
                + ", REQ-007 mindgraph=" + mindgraphProbes
                + " (consumer=" + consumerProbes + ")");
    }

    // ── orchestration ──────────────────────────────────────────────────────────

    /**
     * Runs the full V4 flow if exec files do not already exist for this traceId.
     * Idempotent: if both exec files exist (from a prior run or the shell script), skip compose.
     */
    private void runV4Flow(String traceId, Path diaryExec, Path mindgraphExec) throws Exception {
        if (Files.exists(diaryExec) && Files.size(diaryExec) > 0
                && Files.exists(mindgraphExec) && Files.size(mindgraphExec) > 0) {
            System.out.println("[V4] exec files already present — skipping compose orchestration");
            System.out.println("[V4] diary exec:     " + diaryExec + " (" + execSize(diaryExec) + " bytes)");
            System.out.println("[V4] mindgraph exec: " + mindgraphExec + " (" + execSize(mindgraphExec) + " bytes)");
            return;
        }

        System.out.println("[V4] exec files not found — running live compose flow");
        runLiveFlow(traceId, diaryExec, mindgraphExec);
    }

    /**
     * Drives the full live flow:
     * diary readiness → POST /internal/diaries with traceparent → Kafka wait → flush both → await exec.
     */
    private void runLiveFlow(String traceId, Path diaryExec, Path mindgraphExec) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // pjacoco control readiness
        awaitPjacocoControl(http, "localhost", DIARY_CTL_PORT, "diary");
        awaitPjacocoControl(http, "localhost", MINDGRAPH_CTL_PORT, "mindgraph");

        // diary HTTP readiness
        awaitDiaryHttp(http);

        // POST /internal/diaries with traceparent
        String traceparent = "00-" + traceId + "-" + String.format("%016x", 1L) + "-01";
        System.out.println("[V4] POST /internal/diaries traceparent=" + traceparent);
        HttpResponse<String> postResp = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + DIARY_PORT + "/internal/diaries"))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"userId\":\"u1\",\"title\":\"v4poc-hello\","
                                + "\"content\":\"v4poc-content\",\"primaryEmotion\":\"joy\",\"energyScore\":5}"))
                        .header("Content-Type", "application/json")
                        .header("traceparent", traceparent)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        int status = postResp.statusCode();
        System.out.println("[V4] POST /internal/diaries → HTTP " + status);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("POST /internal/diaries returned " + status
                    + " body=" + postResp.body());
        }

        // Wait for Kafka consumer
        System.out.println("[V4] Waiting " + KAFKA_WAIT_MS + "ms for mindgraph Kafka consumer...");
        Thread.sleep(KAFKA_WAIT_MS);

        // Flush both stores
        flushStore(http, "localhost", DIARY_CTL_PORT, traceId, "diary");
        flushStore(http, "localhost", MINDGRAPH_CTL_PORT, traceId, "mindgraph");

        // Await exec files
        awaitExecFile(diaryExec, "diary");
        awaitExecFile(mindgraphExec, "mindgraph");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void awaitPjacocoControl(HttpClient http, String host, int port, String label)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://" + host + ":" + port + "/__coverage__/test/list"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() < 500) {
                    System.out.println("[V4] " + label + " pjacoco control ready on :" + port);
                    return;
                }
            } catch (Exception ignored) { /* not ready */ }
            Thread.sleep(1500);
        }
        throw new IllegalStateException(label + " pjacoco control not ready on :" + port + " within 30s");
    }

    private void awaitDiaryHttp(HttpClient http) throws Exception {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + DIARY_PORT + "/actuator/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() < 500) {
                    System.out.println("[V4] diary HTTP ready on :" + DIARY_PORT);
                    return;
                }
            } catch (Exception ignored) { /* not ready */ }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("diary HTTP not ready on :" + DIARY_PORT + " within 60s");
    }

    private void flushStore(HttpClient http, String host, int port, String traceId, String label)
            throws Exception {
        String path = "/__coverage__/test/stop?testId=" + traceId + "&result=passed";
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://" + host + ":" + port + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException(label + " flush failed: HTTP " + r.statusCode());
        }
        System.out.println("[V4] " + label + " flush → HTTP " + r.statusCode());
    }

    private void awaitExecFile(Path execFile, String label) throws InterruptedException {
        Instant deadline = Instant.now().plusMillis(EXEC_AWAIT_MS);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Files.exists(execFile) && Files.size(execFile) > 0) {
                    System.out.printf("[V4] %s exec appeared: %s (%d bytes)%n",
                            label, execFile, Files.size(execFile));
                    return;
                }
            } catch (IOException ignored) { /* retry */ }
            Thread.sleep(500);
        }
        throw new IllegalStateException(label + " exec not produced within " + EXEC_AWAIT_MS + "ms at " + execFile);
    }

    private static ExecutionDataStore load(Path execFile) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());
        return loader.getExecutionDataStore();
    }

    /**
     * Counts total covered probes (probe entries where at least one boolean is true)
     * across all classes in the store.
     */
    private static long countCoveredProbes(ExecutionDataStore store) {
        Collection<ExecutionData> entries = store.getContents();
        long total = 0L;
        for (ExecutionData ed : entries) {
            boolean[] probes = ed.getProbes();
            for (boolean p : probes) {
                if (p) total++;
            }
        }
        return total;
    }

    /**
     * Counts covered probes that belong to mindgraph consumer/graph classes:
     * DiaryCreatedConsumer, GraphService, RuleBasedGraphExtractor, etc.
     */
    private static long countConsumerProbes(ExecutionDataStore store) {
        long total = 0L;
        for (ExecutionData ed : store.getContents()) {
            String name = ed.getName(); // internal class name
            if (name.contains("DiaryCreatedConsumer")
                    || name.contains("GraphService")
                    || name.contains("GraphExtractor")
                    || name.contains("mindgraph/event")
                    || name.contains("mindgraph/service")) {
                boolean[] probes = ed.getProbes();
                for (boolean p : probes) {
                    if (p) total++;
                }
            }
        }
        return total;
    }

    private static void printClassSummary(ExecutionDataStore store, String label) {
        Collection<ExecutionData> entries = store.getContents();
        System.out.printf("[V4-%s] classCount=%d classes in exec%n", label, entries.size());
        entries.stream()
                .sorted((a, b) -> Long.compare(
                        countProbes(b.getProbes()), countProbes(a.getProbes())))
                .limit(10)
                .forEach(ed -> {
                    long covered = countProbes(ed.getProbes());
                    if (covered > 0) {
                        System.out.printf("[V4-%s]   %s probes=%d%n", label, ed.getName(), covered);
                    }
                });
    }

    private static long countProbes(boolean[] probes) {
        long n = 0;
        for (boolean p : probes) { if (p) n++; }
        return n;
    }

    private static long execSize(Path path) {
        try { return Files.size(path); } catch (IOException e) { return -1L; }
    }

    // ── path resolution ────────────────────────────────────────────────────────

    private static String traceId() {
        return System.getenv().getOrDefault("V4_TRACE_ID", DEFAULT_TRACE_ID);
    }

    private static Path taintedPlatform() {
        String env = System.getenv("TAINTED_PLATFORM");
        if (env != null && !env.isBlank()) return Paths.get(env);
        return Paths.get(System.getProperty("user.home"),
                "github_tainted-spring", "tainted-spring-platform");
    }

    private static Path diaryExecPath(String traceId) {
        return taintedPlatform().resolve("coverage/diary/" + traceId + ".exec");
    }

    private static Path mindgraphExecPath(String traceId) {
        return taintedPlatform().resolve("coverage/mindgraph/" + traceId + ".exec");
    }
}
