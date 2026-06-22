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
 * PoC gate V3(b): per-request 오버헤드 임계 게이트 — REQ-005.
 *
 * <p>OTel-scope/traceId 경로({@link PjacocoOtelScopeClient})를 사용해 per-request 커버리지 격리의
 * 추가 비용을 측정한다.
 *
 * <h3>측정 항목</h3>
 * <ol>
 *   <li><b>제어 왕복 지연 (flush round-trip)</b>: {@code POST /__coverage__/test/stop?testId=...}
 *       왕복을 100회 반복하고 평균 ms를 구한다. 임계: {@code < 5ms} (로컬 loopback).</li>
 *   <li><b>벽시계 증가율</b>: 60회 요청을 ① per-request 격리(traceparent+flush+load 포함)와
 *       ② baseline(traceparent/flush/load 없는 단순 요청)으로 각각 측정해 증가율을 산출한다.
 *       임계: {@code < 10%}.</li>
 *   <li><b>.exec 아티팩트 비용</b>: 60개 {@code .exec} 파일 개수와 총 바이트를 보고한다.
 *       경고 임계는 없으나 pathological 수치(매우 큰 파일·예상치 못한 개수)를 플래그한다.</li>
 * </ol>
 *
 * <h3>포함 범위 (baseline vs measured)</h3>
 * <ul>
 *   <li><b>Baseline</b>: {@code GET /owners?lastName=} 60회 (traceparent 헤더 없음, flush/load 없음).
 *       petclinic OTel+pjacoco 기동 상태에서 측정 (pjacoco agent는 부착되어 있으나 traceId 없이 요청).</li>
 *   <li><b>Measured</b>: 동일 요청 60회, 각 요청에 traceparent 헤더 추가 + {@code flush(traceId)} +
 *       {@code awaitAndLoad(traceId)} 포함. 즉 per-request 커버리지 격리의 전체 비용(flush+load)이
 *       포함된다.</li>
 * </ul>
 *
 * <h3>실행</h3>
 * <pre>{@code
 * POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OverheadPoc*' \
 *   -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V3OverheadPoc {

    private static final int PETCLINIC_PORT    = 8080;
    private static final int PJACOCO_CTL_PORT  = 6310;
    private static final int BOOT_TIMEOUT_S    = 90;

    private static final int FLUSH_PROBE_COUNT = 100;   // 제어 왕복 측정 횟수
    private static final int REQUEST_COUNT     = 60;    // 벽시계 측정 요청 수

    /** flush 왕복 임계: 5ms */
    private static final double FLUSH_ROUND_TRIP_THRESHOLD_MS = 5.0;
    /** 벽시계 증가율 임계: 10% */
    private static final double WALL_CLOCK_INCREASE_THRESHOLD_PCT = 10.0;
    /** .exec 단일 파일 크기 병리적 임계: 1MB */
    private static final long EXEC_FILE_PATHOLOGICAL_BYTES = 1_000_000L;

    @Test
    @DisplayName("REQ-005: per-request 오버헤드 임계 이내")
    void overheadWithinThreshold() throws Exception {
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path workDir  = Files.createTempDirectory("v3-overhead-");

        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar();

        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(workDir, PJACOCO_CTL_PORT, pjacocoPkg, true);
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        PjacocoOtelScopeClient client = new PjacocoOtelScopeClient("127.0.0.1", PJACOCO_CTL_PORT, workDir);
        HttpClient http = HttpClient.newHttpClient();

        Process proc = launchPetclinicWithOtel(repoRoot, combinedJto);
        try {
            // ── 1. 제어 왕복 지연 측정 (flush POST 100회) ─────────────────────────────
            System.out.println("[V3b] === ① 제어 왕복 지연 측정 (" + FLUSH_PROBE_COUNT + "회) ===");

            // warm-up: 첫 5회는 버림 (JIT warm-up)
            int warmupCount = 5;
            for (int i = 0; i < warmupCount; i++) {
                String warmId = PjacocoOtelScopeClient.traceIdFor(-1 - i);
                String warmTp = PjacocoOtelScopeClient.traceparentFor(warmId);
                hitPetclinic(http, warmTp);
                client.flush(warmId);
            }

            List<Long> flushNanos = new ArrayList<>(FLUSH_PROBE_COUNT);
            for (int i = 0; i < FLUSH_PROBE_COUNT; i++) {
                String traceId    = PjacocoOtelScopeClient.traceIdFor(1000 + i);
                String traceparent = PjacocoOtelScopeClient.traceparentFor(traceId);
                hitPetclinic(http, traceparent);

                long start = System.nanoTime();
                client.flush(traceId);
                long elapsed = System.nanoTime() - start;
                flushNanos.add(elapsed);
            }

            double meanFlushMs = flushNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
            double p95FlushMs  = percentile(flushNanos, 95) / 1_000_000.0;
            System.out.printf("[V3b] flush round-trip: mean=%.3fms  p95=%.3fms  (threshold < %.1fms)%n",
                    meanFlushMs, p95FlushMs, FLUSH_ROUND_TRIP_THRESHOLD_MS);

            // ── 2. 벽시계 측정 ──────────────────────────────────────────────────────
            System.out.println("[V3b] === ② 벽시계 측정 (각 " + REQUEST_COUNT + "회) ===");

            // ── 2a. baseline (traceparent/flush/load 없음) ────────────────────────
            System.out.println("[V3b] baseline 측정 (traceparent/flush/load 없음)...");
            long baselineStart = System.nanoTime();
            for (int i = 0; i < REQUEST_COUNT; i++) {
                hitPetclinicNoTraceparent(http);
            }
            long baselineNs = System.nanoTime() - baselineStart;
            double baselineMs = baselineNs / 1_000_000.0;

            // ── 2b. measured (traceparent + flush + load 포함) ────────────────────
            System.out.println("[V3b] measured 측정 (traceparent+flush+load 포함)...");
            long measuredStart = System.nanoTime();
            for (int i = 0; i < REQUEST_COUNT; i++) {
                String traceId    = PjacocoOtelScopeClient.traceIdFor(2000 + i);
                String traceparent = PjacocoOtelScopeClient.traceparentFor(traceId);
                hitPetclinic(http, traceparent);
                client.flush(traceId);
                client.awaitAndLoad(traceId);
            }
            long measuredNs = System.nanoTime() - measuredStart;
            double measuredMs = measuredNs / 1_000_000.0;

            double increaseMs  = measuredMs - baselineMs;
            double increasePct = (increaseMs / baselineMs) * 100.0;
            System.out.printf("[V3b] baseline=%.1fms  measured=%.1fms  increase=%.1fms (%.2f%%)  " +
                    "(threshold < %.1f%%)%n",
                    baselineMs, measuredMs, increaseMs, increasePct, WALL_CLOCK_INCREASE_THRESHOLD_PCT);

            // ── 3. .exec 아티팩트 비용 ──────────────────────────────────────────────
            System.out.println("[V3b] === ③ .exec 아티팩트 비용 ===");
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
                    System.out.printf("[V3b] ⚠ pathological exec file: %s (%d bytes)%n",
                            f.getFileName(), size);
                    pathological = true;
                }
            }
            System.out.printf("[V3b] exec count=%d  total=%d bytes (%.1f KB)%s%n",
                    execFiles.size(), totalExecBytes, totalExecBytes / 1024.0,
                    pathological ? "  ⚠ PATHOLOGICAL" : "");

            // ── 최종 판정 ───────────────────────────────────────────────────────────
            System.out.println("[V3b] === 최종 판정 ===");
            System.out.printf("[V3b] flush round-trip mean=%.3fms %s (threshold < %.1fms)%n",
                    meanFlushMs,
                    meanFlushMs < FLUSH_ROUND_TRIP_THRESHOLD_MS ? "✅ PASS" : "❌ FAIL",
                    FLUSH_ROUND_TRIP_THRESHOLD_MS);
            System.out.printf("[V3b] wall-clock increase=%.2f%% %s (threshold < %.1f%%)%n",
                    increasePct,
                    increasePct < WALL_CLOCK_INCREASE_THRESHOLD_PCT ? "✅ PASS" : "❌ FAIL",
                    WALL_CLOCK_INCREASE_THRESHOLD_PCT);

            // summary line for grep
            System.out.printf("OVERHEAD round-trip=%.3fms wall-clock=+%.2f%% exec=%d/%dB%n",
                    meanFlushMs, increasePct, execFiles.size(), totalExecBytes);

            assertThat(meanFlushMs)
                    .as("REQ-005 ①: flush round-trip mean < %.1fms (actual=%.3fms)",
                            FLUSH_ROUND_TRIP_THRESHOLD_MS, meanFlushMs)
                    .isLessThan(FLUSH_ROUND_TRIP_THRESHOLD_MS);

            assertThat(increasePct)
                    .as("REQ-005 ②: wall-clock increase < %.1f%% (actual=%.2f%%)",
                            WALL_CLOCK_INCREASE_THRESHOLD_PCT, increasePct)
                    .isLessThan(WALL_CLOCK_INCREASE_THRESHOLD_PCT);

            System.out.println("[V3b] REQ-005 PASS");

        } finally {
            stopProcess(proc, "petclinic-v3b");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

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

        // build petclinic
        buildPetclinic(petclinicDirPath);

        Path libsDir = petclinicDirPath.resolve("build/libs");
        Path petclinicJar = Files.list(libsDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-plain"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("petclinic fat jar not found in " + libsDir));

        String javaBin = resolveJava17();
        System.out.println("[V3b] launching petclinic: " + petclinicJar);
        System.out.println("[V3b] JAVA_TOOL_OPTIONS=" + javaToolOptions);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v3b-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-v3b-overhead");

        Process proc = pb.start();
        System.out.println("[V3b] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V3b] petclinic up");
        return proc;
    }

    private void buildPetclinic(Path petclinicDir) throws Exception {
        System.out.println("[V3b] building petclinic (./gradlew bootJar)...");
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
        System.out.println("[V3b] petclinic build OK");
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

    /** GET /owners?lastName= with traceparent header. */
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

    /** GET /owners?lastName= with NO traceparent header (baseline). */
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
            System.out.println("[V3b] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Compute the Nth percentile of a list of nanosecond values (sorted). */
    private static long percentile(List<Long> nanos, int pct) {
        List<Long> sorted = nanos.stream().sorted().collect(Collectors.toList());
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }
}
