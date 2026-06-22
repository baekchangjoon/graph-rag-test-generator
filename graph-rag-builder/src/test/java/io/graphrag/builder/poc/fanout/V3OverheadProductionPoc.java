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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC gate V3(b) — production-model 재측정 (REQ-005).
 *
 * <h3>배경</h3>
 * <p>이전 {@link V3OverheadPoc}는 per-request 루프 안에서
 * {@code awaitAndLoad(traceId)}(300ms poll)를 호출해 wall-clock +23.83%를 기록했다.
 * 그러나 production fan-out에서 {@code .exec} 로드는 run 종료 후 post-processing 단계에서
 * 일괄 처리된다 — 요청당 critical-path에 포함되지 않는다.
 *
 * <h3>Production-model 측정 범위</h3>
 * <ul>
 *   <li><b>Baseline</b>: {@code GET /owners?lastName=} 60회, traceparent·flush·load 없음.</li>
 *   <li><b>Measured (production model)</b>: 동일 60회, 각 요청에 고유 traceparent 헤더 +
 *       {@code flush(traceId)} 포함. {@code awaitAndLoad}는 <em>루프 밖</em>(post-run)에서 호출.</li>
 * </ul>
 * <p>Post-run load 시간은 별도 보고(fan-out에서 run 이후 amortized, 요청당 critical-path 아님).
 * 이전 flush+load 동기 모델 수치도 참고용으로 함께 기록한다.
 *
 * <h3>실행</h3>
 * <pre>{@code
 * POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OverheadProductionPoc*' \
 *   -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V3OverheadProductionPoc {

    private static final int PETCLINIC_PORT    = 8080;
    private static final int PJACOCO_CTL_PORT  = 6310;
    private static final int BOOT_TIMEOUT_S    = 90;

    private static final int FLUSH_PROBE_COUNT = 100;
    private static final int REQUEST_COUNT     = 60;

    private static final double FLUSH_ROUND_TRIP_THRESHOLD_MS     = 5.0;
    private static final double WALL_CLOCK_INCREASE_THRESHOLD_PCT  = 10.0;
    private static final long   EXEC_FILE_PATHOLOGICAL_BYTES       = 1_000_000L;

    @Test
    @DisplayName("REQ-005: per-request 오버헤드 임계 이내 (production-model: .exec load off critical path)")
    void overheadProductionModel() throws Exception {
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path workDir  = Files.createTempDirectory("v3-overhead-prod-");

        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar();

        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(workDir, PJACOCO_CTL_PORT, pjacocoPkg, true);
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        PjacocoOtelScopeClient client = new PjacocoOtelScopeClient("127.0.0.1", PJACOCO_CTL_PORT, workDir);
        HttpClient http = HttpClient.newHttpClient();

        Process proc = launchPetclinicWithOtel(repoRoot, combinedJto);
        try {
            // ── 1. flush 왕복 지연 (이전 모델과 동일, 참고용) ─────────────────────
            System.out.println("[V3b-prod] === ① flush 왕복 지연 (" + FLUSH_PROBE_COUNT + "회) ===");
            int warmupCount = 5;
            for (int i = 0; i < warmupCount; i++) {
                String warmId = PjacocoOtelScopeClient.traceIdFor(-1 - i);
                hitPetclinic(http, PjacocoOtelScopeClient.traceparentFor(warmId));
                client.flush(warmId);
            }

            List<Long> flushNanos = new ArrayList<>(FLUSH_PROBE_COUNT);
            for (int i = 0; i < FLUSH_PROBE_COUNT; i++) {
                String traceId    = PjacocoOtelScopeClient.traceIdFor(1000 + i);
                hitPetclinic(http, PjacocoOtelScopeClient.traceparentFor(traceId));

                long start = System.nanoTime();
                client.flush(traceId);
                flushNanos.add(System.nanoTime() - start);
            }

            double meanFlushMs = flushNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            double p95FlushMs  = percentile(flushNanos, 95) / 1_000_000.0;
            System.out.printf("[V3b-prod] flush round-trip: mean=%.3fms  p95=%.3fms  (threshold < %.1fms)%n",
                    meanFlushMs, p95FlushMs, FLUSH_ROUND_TRIP_THRESHOLD_MS);

            // ── 2a. baseline ──────────────────────────────────────────────────
            System.out.println("[V3b-prod] === ② baseline 측정 (60회, traceparent/flush/load 없음) ===");
            long baselineStart = System.nanoTime();
            for (int i = 0; i < REQUEST_COUNT; i++) {
                hitPetclinicNoTraceparent(http);
            }
            double baselineMs = (System.nanoTime() - baselineStart) / 1_000_000.0;
            System.out.printf("[V3b-prod] baseline=%.1fms%n", baselineMs);

            // ── 2b. production-model measured (traceparent + flush, NO load inside loop) ──
            System.out.println("[V3b-prod] === ③ production-model 측정 (60회, traceparent+flush, load는 루프 밖) ===");
            List<String> measuredTraceIds = new ArrayList<>(REQUEST_COUNT);
            long measuredStart = System.nanoTime();
            for (int i = 0; i < REQUEST_COUNT; i++) {
                String traceId = PjacocoOtelScopeClient.traceIdFor(2000 + i);
                measuredTraceIds.add(traceId);
                hitPetclinic(http, PjacocoOtelScopeClient.traceparentFor(traceId));
                client.flush(traceId);
                // NOTE: awaitAndLoad is NOT called here — this is the production model.
                // In production fan-out, .exec loading happens post-run (off critical path).
            }
            double prodMeasuredMs = (System.nanoTime() - measuredStart) / 1_000_000.0;

            double prodIncreaseMs  = prodMeasuredMs - baselineMs;
            double prodIncreasePct = (prodIncreaseMs / baselineMs) * 100.0;
            System.out.printf("[V3b-prod] production-model: baseline=%.1fms  measured=%.1fms  " +
                    "increase=%.1fms (%.2f%%)  (threshold < %.1f%%)%n",
                    baselineMs, prodMeasuredMs, prodIncreaseMs, prodIncreasePct,
                    WALL_CLOCK_INCREASE_THRESHOLD_PCT);

            // ── 3. post-run load (off critical path) ─────────────────────────
            System.out.println("[V3b-prod] === ④ post-run .exec load (off critical path, 60개) ===");
            long postRunLoadStart = System.nanoTime();
            for (String traceId : measuredTraceIds) {
                client.awaitAndLoad(traceId);
            }
            double postRunLoadMs = (System.nanoTime() - postRunLoadStart) / 1_000_000.0;
            System.out.printf("[V3b-prod] post-run load=%.1fms (60개 .exec, off critical path)%n",
                    postRunLoadMs);

            // ── 4. .exec 아티팩트 비용 ──────────────────────────────────────────
            System.out.println("[V3b-prod] === ⑤ .exec 아티팩트 비용 ===");
            List<Path> execFiles = Files.list(workDir)
                    .filter(p -> p.getFileName().toString().endsWith(".exec"))
                    .sorted()
                    .collect(Collectors.toList());
            long totalExecBytes = 0L;
            boolean pathological = false;
            for (Path f : execFiles) {
                long size = Files.size(f);
                totalExecBytes += size;
                if (size > EXEC_FILE_PATHOLOGICAL_BYTES) {
                    System.out.printf("[V3b-prod] ⚠ pathological exec file: %s (%d bytes)%n",
                            f.getFileName(), size);
                    pathological = true;
                }
            }
            System.out.printf("[V3b-prod] exec count=%d  total=%d bytes (%.1f KB)%s%n",
                    execFiles.size(), totalExecBytes, totalExecBytes / 1024.0,
                    pathological ? "  ⚠ PATHOLOGICAL" : "");

            // ── 최종 판정 ───────────────────────────────────────────────────────
            System.out.println("[V3b-prod] === 최종 판정 ===");
            System.out.printf("[V3b-prod] flush round-trip mean=%.3fms %s (threshold < %.1fms)%n",
                    meanFlushMs,
                    meanFlushMs < FLUSH_ROUND_TRIP_THRESHOLD_MS ? "✅ PASS" : "❌ FAIL",
                    FLUSH_ROUND_TRIP_THRESHOLD_MS);
            System.out.printf("[V3b-prod] production-model wall-clock increase=%.2f%% %s " +
                    "(threshold < %.1f%%)%n",
                    prodIncreasePct,
                    prodIncreasePct < WALL_CLOCK_INCREASE_THRESHOLD_PCT ? "✅ PASS" : "❌ FAIL",
                    WALL_CLOCK_INCREASE_THRESHOLD_PCT);
            System.out.printf("[V3b-prod] post-run load=%.1fms (off critical path, not subject to threshold)%n",
                    postRunLoadMs);

            // Reference: prior sync model (from task-5-report.md)
            System.out.println("[V3b-prod] [참고] 이전 동기 flush+load 모델: +23.83% (FAIL) — awaitAndLoad 300ms poll 적산");

            // summary line for grep
            System.out.printf("PROD-OVERHEAD round-trip=%.3fms wall-clock-prod=+%.2f%% post-run-load=%.1fms exec=%d/%dB%n",
                    meanFlushMs, prodIncreasePct, postRunLoadMs, execFiles.size(), totalExecBytes);

            assertThat(meanFlushMs)
                    .as("REQ-005 ①: flush round-trip mean < %.1fms (actual=%.3fms)",
                            FLUSH_ROUND_TRIP_THRESHOLD_MS, meanFlushMs)
                    .isLessThan(FLUSH_ROUND_TRIP_THRESHOLD_MS);

            assertThat(prodIncreasePct)
                    .as("REQ-005 ②: production-model wall-clock increase < %.1f%% (actual=%.2f%%)",
                            WALL_CLOCK_INCREASE_THRESHOLD_PCT, prodIncreasePct)
                    .isLessThan(WALL_CLOCK_INCREASE_THRESHOLD_PCT);

            System.out.println("[V3b-prod] REQ-005 PASS (production model)");

        } finally {
            stopProcess(proc, "petclinic-v3b-prod");
        }
    }

    // ── helpers (identical to V3OverheadPoc) ─────────────────────────────────

    private Path resolveOtelJar() {
        String otelJar = System.getenv().getOrDefault("OTEL_JAR",
                System.getProperty("user.home")
                        + "/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar");
        Path path = Paths.get(otelJar);
        assertThat(path).as("OTel jar must exist (set OTEL_JAR env)").isRegularFile();
        return path;
    }

    private Process launchPetclinicWithOtel(Path repoRoot, String javaToolOptions) throws Exception {
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

        String javaBin = resolveJava17();
        System.out.println("[V3b-prod] launching petclinic: " + petclinicJar);
        System.out.println("[V3b-prod] JAVA_TOOL_OPTIONS=" + javaToolOptions);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v3b-prod-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-v3b-prod-overhead");

        Process proc = pb.start();
        System.out.println("[V3b-prod] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V3b-prod] petclinic up");
        return proc;
    }

    private void buildPetclinic(Path petclinicDir) throws Exception {
        System.out.println("[V3b-prod] building petclinic (./gradlew bootJar)...");
        ProcessBuilder pb = new ProcessBuilder("./gradlew", "bootJar", "-q")
                .directory(petclinicDir.toFile())
                .redirectErrorStream(true);
        String petclinicJava = System.getenv("PETCLINIC_JAVA");
        if (petclinicJava != null && !petclinicJava.isBlank()) {
            pb.environment().put("JAVA_HOME", petclinicJava);
        } else {
            String java17Home = detectJava17Home();
            if (java17Home != null) pb.environment().put("JAVA_HOME", java17Home);
        }
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            out = r.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("petclinic build failed (exit " + code + "):\n" + out);
        System.out.println("[V3b-prod] petclinic build OK");
    }

    private String resolveJava17() {
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
        } catch (Exception ignored) { /* not macOS or no java_home */ }
        return null;
    }

    private void awaitPort(int port, Process proc) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plusSeconds(BOOT_TIMEOUT_S);
        while (Instant.now().isBefore(deadline)) {
            if (!proc.isAlive()) {
                throw new IllegalStateException("petclinic process exited early (pid=" + proc.pid() + ")");
            }
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/owners?lastName="))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() < 500) return;
            } catch (Exception ignored) { /* not ready */ }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("petclinic did not come up in " + BOOT_TIMEOUT_S + "s");
    }

    private void hitPetclinic(HttpClient http, String traceparent) throws Exception {
        HttpResponse<Void> r = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + PETCLINIC_PORT + "/owners?lastName="))
                        .header("traceparent", traceparent)
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic returned " + r.statusCode());
        }
    }

    private void hitPetclinicNoTraceparent(HttpClient http) throws Exception {
        HttpResponse<Void> r = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + PETCLINIC_PORT + "/owners?lastName="))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic returned " + r.statusCode());
        }
    }

    private void stopProcess(Process proc, String label) {
        if (proc.isAlive()) {
            System.out.println("[V3b-prod] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static long percentile(List<Long> nanos, int pct) {
        List<Long> sorted = nanos.stream().sorted().collect(Collectors.toList());
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }
}
