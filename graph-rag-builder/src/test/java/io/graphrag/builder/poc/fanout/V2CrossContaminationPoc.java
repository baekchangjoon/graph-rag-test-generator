package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC gate V2: 동시 2 엔드포인트 커버리지 교차오염 0 — REQ-002.
 *
 * <p>단일 pjacoco-attached SUT에서 두 워커가 동시에 서로소 클래스를 갖는 엔드포인트를 탐색할 때,
 * 각 traceId `.exec`에 상대방의 전용 클래스 probe가 0이어야 한다.
 *
 * <p>엔드포인트 선택:
 * <ul>
 *   <li>A: {@code GET /owners?lastName=} → {@code OwnerController}
 *       (internal: {@code org/springframework/samples/petclinic/owner/OwnerController})</li>
 *   <li>B: {@code GET /vets.html} → {@code VetController}
 *       (internal: {@code org/springframework/samples/petclinic/vet/VetController})</li>
 * </ul>
 *
 * <p>실행: {@code POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V2CrossContaminationPoc*'
 *   -Dpjacoco.agent.jar=$(bash e2e/poc-fanout/install-pjacoco.sh | tail -1)}
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V2CrossContaminationPoc {

    private static final int PETCLINIC_PORT = 8080;
    private static final int PJACOCO_CTL_PORT = 6310;
    private static final int BOOT_TIMEOUT_S = 90;
    private static final int ROUNDS = 5; // rounds of concurrent pairs to ensure overlap

    /** Internal class name (JVM form) for OwnerController — exclusive to endpoint A. */
    private static final String OWNER_CONTROLLER =
            "org/springframework/samples/petclinic/owner/OwnerController";

    /** Internal class name (JVM form) for VetController — exclusive to endpoint B. */
    private static final String VET_CONTROLLER =
            "org/springframework/samples/petclinic/vet/VetController";

    @Test
    @DisplayName("REQ-002: 동시 2EP 커버리지 교차오염 0")
    void concurrentEndpoints_noCrossContamination() throws Exception {
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path workDir = Files.createTempDirectory("v2-cross-contamination-");

        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar();
        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(workDir, PJACOCO_CTL_PORT, pjacocoPkg, true);
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        PjacocoOtelScopeClient client = new PjacocoOtelScopeClient("127.0.0.1", PJACOCO_CTL_PORT, workDir);

        System.out.println("[V2] workDir=" + workDir);
        System.out.println("[V2] combinedJto=" + combinedJto);

        Process petclinicProc = launchPetclinic(repoRoot, combinedJto);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Use request indices: A=0..ROUNDS-1, B=ROUNDS..2*ROUNDS-1
            // Accumulate coverage from multiple concurrent rounds; use the last pair for assertion
            // (any round is sufficient — if contamination exists it will show in any exec)
            String lastTraceA = null;
            String lastTraceB = null;

            for (int round = 0; round < ROUNDS; round++) {
                String traceA = PjacocoOtelScopeClient.traceIdFor(round * 2);
                String traceB = PjacocoOtelScopeClient.traceIdFor(round * 2 + 1);
                String traceparentA = PjacocoOtelScopeClient.traceparentFor(traceA);
                String traceparentB = PjacocoOtelScopeClient.traceparentFor(traceB);

                System.out.printf("[V2] round=%d traceA=%.8s… traceB=%.8s…%n", round, traceA, traceB);

                // Fire A and B concurrently
                CompletableFuture<Void> futA = CompletableFuture.runAsync(
                        () -> hitEndpoint("http://127.0.0.1:" + PETCLINIC_PORT + "/owners?lastName=",
                                traceparentA),
                        pool);
                CompletableFuture<Void> futB = CompletableFuture.runAsync(
                        () -> hitEndpoint("http://127.0.0.1:" + PETCLINIC_PORT + "/vets.html",
                                traceparentB),
                        pool);
                CompletableFuture.allOf(futA, futB).get();

                // Flush both stores
                client.flush(traceA);
                client.flush(traceB);

                lastTraceA = traceA;
                lastTraceB = traceB;
            }

            assertThat(lastTraceA).as("lastTraceA must be set").isNotNull();
            assertThat(lastTraceB).as("lastTraceB must be set").isNotNull();

            // Load the last round's exec files (they are the freshest, each isolated per traceId)
            ExecutionDataStore storeA = client.awaitAndLoad(lastTraceA);
            ExecutionDataStore storeB = client.awaitAndLoad(lastTraceB);

            long ownAinA = coveredProbeCount(storeA, OWNER_CONTROLLER);
            long vetBinB = coveredProbeCount(storeB, VET_CONTROLLER);
            long ownerInB = coveredProbeCount(storeB, OWNER_CONTROLLER); // must be 0
            long vetInA = coveredProbeCount(storeA, VET_CONTROLLER);     // must be 0

            System.out.printf("[V2] OwnerController covered probes in A.exec = %d (own coverage)%n", ownAinA);
            System.out.printf("[V2] VetController   covered probes in B.exec = %d (own coverage)%n", vetBinB);
            System.out.printf("[V2] OwnerController covered probes in B.exec = %d (must be 0)%n", ownerInB);
            System.out.printf("[V2] VetController   covered probes in A.exec = %d (must be 0)%n", vetInA);

            // Each side must have covered its own controller (otherwise the test proves nothing)
            assertThat(ownAinA)
                    .as("A's .exec must cover OwnerController probes (endpoint A was actually exercised)")
                    .isGreaterThan(0);
            assertThat(vetBinB)
                    .as("B's .exec must cover VetController probes (endpoint B was actually exercised)")
                    .isGreaterThan(0);

            // Cross-contamination gate: zero probes from the other side
            assertThat(ownerInB)
                    .as("REQ-002: B's .exec must contain ZERO covered probes in OwnerController (A's exclusive class)")
                    .isZero();
            assertThat(vetInA)
                    .as("REQ-002: A's .exec must contain ZERO covered probes in VetController (B's exclusive class)")
                    .isZero();

            System.out.printf("[V2] V2 PASS — contamination=0, ownA=%d ownB=%d%n", ownAinA, vetBinB);

        } finally {
            pool.shutdownNow();
            stopProcess(petclinicProc, "petclinic");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Count covered (true) probes for the given internal class name across the store. */
    private static long coveredProbeCount(ExecutionDataStore store, String internalClassName) {
        // ExecutionDataStore.get(long) requires the class id; use getContents() to find by name.
        return store.getContents().stream()
                .filter(d -> internalClassName.equals(d.getName()))
                .mapToLong(d -> {
                    boolean[] probes = d.getProbes();
                    if (probes == null) return 0L;
                    long count = 0;
                    for (boolean p : probes) if (p) count++;
                    return count;
                })
                .sum();
    }

    private void hitEndpoint(String url, String traceparent) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("traceparent", traceparent)
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 500) {
                throw new IllegalStateException("petclinic HTTP " + r.statusCode() + " for " + url);
            }
        } catch (Exception e) {
            throw new RuntimeException("hitEndpoint failed: " + url, e);
        }
    }

    private Path resolveOtelJar() {
        String otelJar = System.getenv().getOrDefault("OTEL_JAR",
                System.getProperty("user.home")
                        + "/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar");
        Path path = Paths.get(otelJar);
        assertThat(path).as("OTel jar must exist (set OTEL_JAR env or use default path)").isRegularFile();
        return path;
    }

    private Process launchPetclinic(Path repoRoot, String javaToolOptions) throws Exception {
        String petclinicDir = System.getenv().getOrDefault("PETCLINIC_DIR",
                System.getProperty("user.home") + "/github_spring-petclinic/spring-petclinic");
        Path libsDir = Paths.get(petclinicDir, "build/libs");
        Path petclinicJar = Files.list(libsDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-plain"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("petclinic fat jar not found in " + libsDir));

        String javaHome = System.getenv("PETCLINIC_JAVA");
        String javaBin;
        if (javaHome != null && !javaHome.isBlank()) {
            javaBin = javaHome + "/bin/java";
        } else {
            javaBin = detectJava17() != null ? detectJava17() + "/bin/java" : "java";
        }

        System.out.println("[V2] launching petclinic: " + petclinicJar);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v2-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-v2-poc");

        Process proc = pb.start();
        System.out.println("[V2] petclinic PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V2] petclinic up");
        return proc;
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
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new IllegalStateException("petclinic did not come up in " + BOOT_TIMEOUT_S + "s");
    }

    private String detectJava17() {
        try {
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", "17")
                    .redirectErrorStream(true).start();
            String home = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return home.isBlank() ? null : home;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void stopProcess(Process proc, String label) {
        if (proc != null && proc.isAlive()) {
            System.out.println("[V2] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
