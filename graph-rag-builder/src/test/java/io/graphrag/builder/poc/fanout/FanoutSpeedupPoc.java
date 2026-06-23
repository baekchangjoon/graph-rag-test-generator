package io.graphrag.builder.poc.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC gate REQ-011: 병렬 fan-out speedup + flush 큐 병목 실측.
 *
 * <p>격리 정확성(V2)·비용(REQ-010)은 이미 검증됐다. 본 테스트는 "병렬화의 실제 목적(속도향상)"을
 * 정량화한다. 동일 총 워크로드(W×B 요청)를 순차(P=1)와 병렬(P=2·4·8)로 실행해 wall-clock speedup을
 * 실측하고, 백그라운드 flush 큐의 최대 깊이·드레인 시간·병목 여부를 보고한다.
 *
 * <h3>워크로드 모델</h3>
 * <ul>
 *   <li>워커 수 W={@value #WORKERS}: 각 워커는 서로 다른 petclinic 엔드포인트를 탐색.</li>
 *   <li>워커당 요청 수 B={@value #BUDGET}: per-request traceparent + async flush.</li>
 *   <li>총 요청 = W×B = {@value #WORKERS}×{@value #BUDGET}.</li>
 * </ul>
 *
 * <h3>측정 1 — Speedup curve</h3>
 * <p>P=1·2·4·8 각각의 wall-clock을 워밍업 후 RUNS회 반복해 중앙값을 취한다.
 * speedup(P) = T_seq / T_p. speedup(8) &gt; 1이면 병렬화가 실질적으로 빠름을 확인.
 *
 * <h3>측정 2 — Flush 큐 병목 (경량 SUT)</h3>
 * <p>petclinic + host JVM → flush ≈ 4ms. 큐 최대 깊이·드레인이 작으면 flush가 병목이 아님을 확인.
 *
 * <h3>측정 3 — Heavy-flush 시뮬레이션</h3>
 * <p>각 flush에 {@value #HEAVY_FLUSH_DELAY_MS}ms 인공 지연을 주입해 무거운 SUT를 모사.
 * 작은 flush pool(1~2개)에서 큐가 백업(무한 성장/드레인 지연)되고, 크기조정 풀(≈R×C)에서 따라잡는지 확인.
 *
 * <h3>실행</h3>
 * <pre>{@code
 * POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*FanoutSpeedupPoc*' \
 *   -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)
 * }</pre>
 *
 * <h3>판정 기준 (REQ-011)</h3>
 * <ul>
 *   <li>speedup(8) &gt; 1.0 → 병렬화가 순차보다 빠름(A 전략의 핵심 주장)</li>
 *   <li>flush 큐 드레인이 전체 런타임보다 짧음 → flush가 병목 아님(경량 SUT)</li>
 *   <li>heavy-flush small-pool → 큐 백업 관측; sized-pool → 따라잡음</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class FanoutSpeedupPoc {

    private static final int PETCLINIC_PORT   = 8080;
    private static final int PJACOCO_CTL_PORT = 6315;  // 충돌 방지용 전용 포트
    private static final int BOOT_TIMEOUT_S   = 90;

    /** 워커 수 (fan-out 병렬도의 최대 P와 동일) */
    private static final int WORKERS = 8;

    /** 워커당 요청 수 (exploration budget) */
    private static final int BUDGET = 20;

    /** 총 요청 수 = WORKERS × BUDGET */
    private static final int TOTAL_REQUESTS = WORKERS * BUDGET;

    /** 워밍업 반복 (버림) */
    private static final int WARMUP_RUNS = 2;

    /** 측정 반복 (중앙값 취득) */
    private static final int TIMED_RUNS = 3;

    /** heavy-flush 시뮬레이션 인공 지연 (ms) — diary 실측 pjacoco-internal cost */
    private static final long HEAVY_FLUSH_DELAY_MS = 86L;

    /** drain 최대 대기 (s) */
    private static final long DRAIN_TIMEOUT_S = 120;

    /**
     * 워커별 엔드포인트 — 각 워커가 서로 다른 경로를 탐색해 petclinic의 다양한 코드 경로를 커버.
     * W=8개이므로 8개 엔드포인트 정의.
     */
    private static final List<String> WORKER_ENDPOINTS = List.of(
            "/owners?lastName=",            // worker-0: all owners (results-found arm)
            "/owners?lastName=ZZZNONE",     // worker-1: no results arm
            "/owners?lastName=Davis",       // worker-2: specific owner search
            "/vets.html",                   // worker-3: vets listing
            "/owners?lastName=Franklin",    // worker-4: specific owner search
            "/owners?lastName=Coleman",     // worker-5: specific owner search
            "/owners?lastName=Black",       // worker-6: specific owner search
            "/owners?lastName=Escobito"     // worker-7: specific owner search
    );

    @Test
    @DisplayName("REQ-011: 병렬 fan-out speedup + flush 큐 병목")
    void fanoutSpeedup_parallelFasterThanSequential_flushNotBottleneck() throws Exception {
        Path workDir = Files.createTempDirectory("fanout-speedup-");

        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar();

        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(workDir, PJACOCO_CTL_PORT, pjacocoPkg, true);
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        PjacocoOtelScopeClient client = new PjacocoOtelScopeClient("127.0.0.1", PJACOCO_CTL_PORT, workDir);

        System.out.printf("[REQ-011] workload: W=%d workers × B=%d requests = %d total%n",
                WORKERS, BUDGET, TOTAL_REQUESTS);

        Process proc = launchPetclinic(otelJar, pjacocoJto, combinedJto);
        try {
            // ── 1. 워밍업 ──────────────────────────────────────────────────────
            System.out.println("[REQ-011] === 워밍업 (P=8, " + WARMUP_RUNS + "회, 버림) ===");
            for (int r = 0; r < WARMUP_RUNS; r++) {
                runWorkload(client, 8, r * 100_000, false, 0L);
            }

            // ── 2. Speedup curve: P=1·2·4·8 ────────────────────────────────────
            int[] parallelism = {1, 2, 4, 8};
            long[] medianMs = new long[parallelism.length];
            int baseOffset = 200_000;

            System.out.println("[REQ-011] === 측정: speedup curve (P=1·2·4·8) ===");
            for (int pi = 0; pi < parallelism.length; pi++) {
                int p = parallelism[pi];
                long[] runs = new long[TIMED_RUNS];
                for (int r = 0; r < TIMED_RUNS; r++) {
                    int idxOffset = baseOffset + pi * 1_000_000 + r * 10_000;
                    RunResult result = runWorkload(client, p, idxOffset, false, 0L);
                    runs[r] = result.wallClockMs;
                    System.out.printf("[REQ-011]   P=%d run%d: wall=%.0fms  queueMax=%d  drainMs=%.0fms%n",
                            p, r, (double) result.wallClockMs, result.queueMaxDepth, (double) result.drainMs);
                }
                java.util.Arrays.sort(runs);
                medianMs[pi] = runs[TIMED_RUNS / 2];
                System.out.printf("[REQ-011] P=%d median=%.0fms%n", p, (double) medianMs[pi]);
            }

            long tSeq = medianMs[0];  // P=1
            System.out.println("[REQ-011] === Speedup curve ===");
            System.out.printf("[REQ-011] %4s  %8s  %10s%n", "P", "median(ms)", "speedup");
            double speedup8 = 0.0;
            for (int pi = 0; pi < parallelism.length; pi++) {
                double speedup = (double) tSeq / medianMs[pi];
                System.out.printf("[REQ-011] %4d  %8d  %10.2fx%n", parallelism[pi], medianMs[pi], speedup);
                if (parallelism[pi] == 8) speedup8 = speedup;
            }

            // ── 3. Flush 큐 병목 측정 (경량 SUT, P=8) ─────────────────────────
            System.out.println("[REQ-011] === flush 큐 병목 측정 (경량 SUT, P=8) ===");
            RunResult lightResult = runWorkload(client, 8, 3_000_000, false, 0L);
            System.out.printf("[REQ-011] light-SUT  wall=%.0fms  queueMax=%d  drain=%.0fms%n",
                    (double) lightResult.wallClockMs, lightResult.queueMaxDepth, (double) lightResult.drainMs);
            boolean lightDrainFast = lightResult.drainMs < lightResult.wallClockMs;
            System.out.printf("[REQ-011] light flush bottleneck check: drain(%.0fms) < wall(%.0fms) → %s%n",
                    (double) lightResult.drainMs, (double) lightResult.wallClockMs,
                    lightDrainFast ? "flush NOT bottleneck" : "flush MAY BE bottleneck");

            // ── 4. Heavy-flush 시뮬레이션 ───────────────────────────────────────
            System.out.printf("[REQ-011] === heavy-flush 시뮬레이션 (delay=%dms) ===%n", HEAVY_FLUSH_DELAY_MS);

            // 요청율 추정: TOTAL_REQUESTS / (tSeq wall-clock of P=8 run)
            double requestRatePerSec = TOTAL_REQUESTS / (lightResult.wallClockMs / 1000.0);
            double neededPoolSize = requestRatePerSec * (HEAVY_FLUSH_DELAY_MS / 1000.0);
            System.out.printf("[REQ-011] request rate ≈ %.1f req/s  flush-cost=%dms  needed pool ≈ %.1f threads%n",
                    requestRatePerSec, HEAVY_FLUSH_DELAY_MS, neededPoolSize);

            // 4a. Small pool = 2 (expect queue backup)
            int smallPool = 2;
            System.out.printf("[REQ-011] --- heavy-flush small pool (flushPool=%d) ---%n", smallPool);
            RunResult heavySmall = runWorkloadHeavyFlush(client, 8, 4_000_000, HEAVY_FLUSH_DELAY_MS, smallPool);
            System.out.printf("[REQ-011] heavy-small  wall=%.0fms  queueMax=%d  drain=%.0fms%n",
                    (double) heavySmall.wallClockMs, heavySmall.queueMaxDepth, (double) heavySmall.drainMs);

            // 4b. Sized pool = ceil(neededPoolSize) + 2 margin
            int sizedPool = Math.max(4, (int) Math.ceil(neededPoolSize) + 2);
            System.out.printf("[REQ-011] --- heavy-flush sized pool (flushPool=%d, needed≈%.1f) ---%n",
                    sizedPool, neededPoolSize);
            RunResult heavySized = runWorkloadHeavyFlush(client, 8, 5_000_000, HEAVY_FLUSH_DELAY_MS, sizedPool);
            System.out.printf("[REQ-011] heavy-sized  wall=%.0fms  queueMax=%d  drain=%.0fms%n",
                    (double) heavySized.wallClockMs, heavySized.queueMaxDepth, (double) heavySized.drainMs);

            // ── 5. Summary ───────────────────────────────────────────────────────
            System.out.println("[REQ-011] === 최종 요약 ===");
            System.out.printf("[REQ-011] T_seq(P=1)=%.0fms%n", (double) tSeq);
            System.out.printf("[REQ-011] speedup(P=8)=%.2fx → %s%n", speedup8,
                    speedup8 > 1.0 ? "✅ 병렬이 순차보다 빠름 (A 전략 speedup 확인)" : "❌ speedup ≤ 1 (SUT 포화)");
            System.out.printf("[REQ-011] flush queue (light SUT): queueMax=%d  drain=%.0fms  → %s%n",
                    lightResult.queueMaxDepth, (double) lightResult.drainMs,
                    lightDrainFast ? "✅ flush NOT bottleneck" : "⚠ flush may be bottleneck");
            System.out.printf("[REQ-011] heavy-flush small-pool(=%d): queueMax=%d  drain=%.0fms%n",
                    smallPool, heavySmall.queueMaxDepth, (double) heavySmall.drainMs);
            System.out.printf("[REQ-011] heavy-flush sized-pool(=%d): queueMax=%d  drain=%.0fms%n",
                    sizedPool, heavySized.queueMaxDepth, (double) heavySized.drainMs);
            System.out.printf("[REQ-011] empirical flush-pool sizing rule: pool ≥ ceil(R×C) ≈ ceil(%.1f req/s × %.3fs) = %d threads%n",
                    requestRatePerSec, HEAVY_FLUSH_DELAY_MS / 1000.0, (int) Math.ceil(neededPoolSize));

            // ── 6. grep-friendly summary line ────────────────────────────────────
            System.out.printf("FANOUT-SPEEDUP T_seq=%dms T_p2=%dms T_p4=%dms T_p8=%dms " +
                    "speedup_p8=%.2fx flush_queueMax=%d flush_drain=%.0fms " +
                    "heavy_small_drain=%.0fms heavy_sized_drain=%.0fms " +
                    "needed_pool=%.1f%n",
                    tSeq, medianMs[1], medianMs[2], medianMs[3], speedup8,
                    lightResult.queueMaxDepth, (double) lightResult.drainMs,
                    (double) heavySmall.drainMs, (double) heavySized.drainMs, neededPoolSize);

            // ── 7. Assertions ─────────────────────────────────────────────────────
            // Core claim: P=8 병렬이 순차보다 빠름
            assertThat(speedup8)
                    .as("REQ-011 ①: speedup(P=8) > 1.0 — parallel faster than sequential " +
                            "(T_seq=%dms  T_p8=%dms  speedup=%.2fx)", tSeq, medianMs[3], speedup8)
                    .isGreaterThan(1.0);

            // flush drain < wall-clock (경량 SUT에서 flush가 병목이 아님)
            assertThat(lightResult.drainMs)
                    .as("REQ-011 ②: flush drain (%.0fms) < wall-clock (%.0fms) — flush not on critical path",
                            (double) lightResult.drainMs, (double) lightResult.wallClockMs)
                    .isLessThan(lightResult.wallClockMs);

            // heavy-flush sized pool drains faster than small pool (sized pool keeps up better)
            assertThat(heavySized.drainMs)
                    .as("REQ-011 ③: sized-pool drain (%.0fms) ≤ small-pool drain (%.0fms) — bigger pool handles heavy flush better",
                            (double) heavySized.drainMs, (double) heavySmall.drainMs)
                    .isLessThanOrEqualTo(heavySmall.drainMs);

            System.out.println("[REQ-011] ✅ REQ-011 PASS — speedup 실측 완료, flush 큐 병목 분석 완료");

        } finally {
            stopProcess(proc, "petclinic-speedup");
        }
    }

    // ── Workload runner ───────────────────────────────────────────────────────

    /** 실제 플러시가 있는 표준 워크로드 실행 결과 */
    private static class RunResult {
        final long wallClockMs;
        final int queueMaxDepth;
        final long drainMs;

        RunResult(long wallClockMs, int queueMaxDepth, long drainMs) {
            this.wallClockMs = wallClockMs;
            this.queueMaxDepth = queueMaxDepth;
            this.drainMs = drainMs;
        }
    }

    /**
     * 표준 워크로드: W워커 × B요청, per-request traceparent + async flush (인공 지연 없음).
     * flushPool 크기는 자동으로 넉넉하게 설정 (WORKERS × 2).
     */
    private RunResult runWorkload(PjacocoOtelScopeClient client, int parallelism, int idxOffset,
                                   boolean heavyFlush, long delayMs) throws Exception {
        int flushPoolSize = Math.max(WORKERS * 2, 4);
        return runWorkloadHeavyFlush(client, parallelism, idxOffset, delayMs, flushPoolSize);
    }

    /**
     * 워크로드 실행 (flush-pool 크기 지정 가능 — heavy-flush 시뮬레이션용).
     *
     * <p>총 {@value #TOTAL_REQUESTS}개 요청을 {@code parallelism}개 스레드 풀로 분배.
     * 각 "워커"(스레드 태스크)는 자신의 엔드포인트에 {@value #BUDGET}번 요청하고 각 요청 후
     * flush를 background pool에 fire-and-forget으로 dispatch.
     */
    private RunResult runWorkloadHeavyFlush(PjacocoOtelScopeClient client, int parallelism,
                                             int idxOffset, long flushDelayMs, int flushPoolSize) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ExecutorService workerPool = Executors.newFixedThreadPool(parallelism);
        ExecutorService flushPool = Executors.newFixedThreadPool(flushPoolSize);

        // 큐 깊이 모니터링용: LinkedBlockingQueue로 flush 작업 수를 추적
        AtomicInteger pendingFlushes = new AtomicInteger(0);
        AtomicInteger maxQueueDepth  = new AtomicInteger(0);
        List<Future<?>> allFlushFutures = new ArrayList<>(TOTAL_REQUESTS);

        // wall-clock: 모든 워커 태스크 시작 시점 ~ 모든 request 완료 시점
        long wallStart = System.currentTimeMillis();
        List<Future<?>> workerFutures = new ArrayList<>(WORKERS);

        for (int w = 0; w < WORKERS; w++) {
            final int workerIdx = w;
            final int workerOffset = idxOffset + w * BUDGET;
            workerFutures.add(workerPool.submit(() -> {
                String endpoint = WORKER_ENDPOINTS.get(workerIdx % WORKER_ENDPOINTS.size());
                for (int b = 0; b < BUDGET; b++) {
                    int reqIdx = workerOffset + b;
                    String traceId = PjacocoOtelScopeClient.traceIdFor(reqIdx);
                    String traceparent = PjacocoOtelScopeClient.traceparentFor(traceId);
                    try {
                        sendRequest(http, endpoint, traceparent);
                        // fire-and-forget flush
                        int depth = pendingFlushes.incrementAndGet();
                        maxQueueDepth.accumulateAndGet(depth, Math::max);
                        String tid = traceId;
                        Future<?> flushFuture = flushPool.submit(() -> {
                            try {
                                if (flushDelayMs > 0) {
                                    Thread.sleep(flushDelayMs);
                                }
                                client.flush(tid);
                            } catch (Exception e) {
                                // 측정 목적이므로 flush 오류는 경고만
                                System.out.printf("[REQ-011] ⚠ flush error traceId=%s: %s%n",
                                        tid.substring(0, 8), e.getMessage());
                            } finally {
                                pendingFlushes.decrementAndGet();
                            }
                        });
                        synchronized (allFlushFutures) {
                            allFlushFutures.add(flushFuture);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("request failed: " + endpoint, e);
                    }
                }
            }));
        }

        // 모든 워커(request 루프) 완료 대기
        for (Future<?> wf : workerFutures) {
            wf.get(120, TimeUnit.SECONDS);
        }
        long wallClockMs = System.currentTimeMillis() - wallStart;

        // drain: 모든 flush 완료 대기 — drain 시간 측정
        long drainStart = System.currentTimeMillis();
        List<Future<?>> flushSnapshot;
        synchronized (allFlushFutures) {
            flushSnapshot = new ArrayList<>(allFlushFutures);
        }
        for (Future<?> ff : flushSnapshot) {
            try { ff.get(DRAIN_TIMEOUT_S, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        long drainMs = System.currentTimeMillis() - drainStart;

        workerPool.shutdownNow();
        flushPool.shutdown();
        try {
            flushPool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        return new RunResult(wallClockMs, maxQueueDepth.get(), drainMs);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private void sendRequest(HttpClient http, String endpoint, String traceparent) throws Exception {
        String url = "http://127.0.0.1:" + PETCLINIC_PORT + endpoint;
        HttpResponse<Void> r = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("traceparent", traceparent)
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic HTTP " + r.statusCode() + " for " + endpoint);
        }
    }

    // ── Petclinic launch ─────────────────────────────────────────────────────

    private Process launchPetclinic(Path otelJar, String pjacocoJto, String combinedJto) throws Exception {
        String petclinicDir = System.getenv().getOrDefault("PETCLINIC_DIR",
                System.getProperty("user.home") + "/github_spring-petclinic/spring-petclinic");
        Path petclinicDirPath = Paths.get(petclinicDir);

        buildPetclinic(petclinicDirPath);

        Path libsDir = petclinicDirPath.resolve("build/libs");
        Path petclinicJar = Files.list(libsDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-plain"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("petclinic fat jar not found in " + libsDir));

        String javaBin = resolveJavaBin();
        System.out.println("[REQ-011] launching petclinic: " + petclinicJar);
        System.out.println("[REQ-011] JAVA_TOOL_OPTIONS=" + combinedJto);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-fanout-speedup.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", combinedJto);
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-fanout-speedup");

        Process proc = pb.start();
        System.out.println("[REQ-011] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[REQ-011] petclinic up");
        return proc;
    }

    private void buildPetclinic(Path petclinicDir) throws Exception {
        System.out.println("[REQ-011] building petclinic (./gradlew bootJar -q)...");
        ProcessBuilder pb = new ProcessBuilder("./gradlew", "bootJar", "-q")
                .directory(petclinicDir.toFile())
                .redirectErrorStream(true);
        String java17Home = detectJava17Home();
        if (java17Home != null) pb.environment().put("JAVA_HOME", java17Home);
        String petclinicJava = System.getenv("PETCLINIC_JAVA");
        if (petclinicJava != null && !petclinicJava.isBlank()) {
            pb.environment().put("JAVA_HOME", petclinicJava);
        }
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            out = r.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("petclinic build failed (exit " + code + "):\n" + out);
        System.out.println("[REQ-011] petclinic build OK");
    }

    private void awaitPort(int port, Process proc) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plusSeconds(BOOT_TIMEOUT_S);
        while (Instant.now().isBefore(deadline)) {
            if (!proc.isAlive()) {
                throw new IllegalStateException("petclinic exited early (pid=" + proc.pid() + ")");
            }
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/owners?lastName="))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() < 500) return;
            } catch (Exception ignored) { }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("petclinic did not come up in " + BOOT_TIMEOUT_S + "s");
    }

    private Path resolveOtelJar() {
        String otelJar = System.getenv().getOrDefault("OTEL_JAR",
                System.getProperty("user.home")
                        + "/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar");
        Path path = Paths.get(otelJar);
        assertThat(path).as("OTel jar must exist (set OTEL_JAR env)").isRegularFile();
        return path;
    }

    private String resolveJavaBin() {
        String petclinicJava = System.getenv("PETCLINIC_JAVA");
        if (petclinicJava != null && !petclinicJava.isBlank()) return petclinicJava + "/bin/java";
        String java17Home = detectJava17Home();
        if (java17Home != null) return java17Home + "/bin/java";
        return "java";
    }

    private String detectJava17Home() {
        try {
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", "17")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String home = r.lines().collect(Collectors.joining()).trim();
                p.waitFor();
                if (!home.isBlank()) return home;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void stopProcess(Process proc, String label) {
        if (proc.isAlive()) {
            System.out.println("[REQ-011] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
