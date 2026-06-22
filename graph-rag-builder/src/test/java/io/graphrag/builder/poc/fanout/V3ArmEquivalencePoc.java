package io.graphrag.builder.poc.fanout;

import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.CoverageFingerprint;
import io.graphrag.builder.coverage.JacocoAgent;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Disabled;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * PoC gate V3(a): per-request testId arm 등가 (correctness) — REQ-004.
 *
 * <p>Vanilla 벡터 (JaCoCo tcpserver dump(reset=true)) 와 pjacoco 벡터
 * (per-request testId start→request→stop→ExecFileLoader) 에 동일 입력 시퀀스를 투입해
 * {@link CoverageFingerprint#of} 로 산출된 coverageKey 집합이 일치함을 검증한다.
 *
 * <p>불일치 → V3(a) FAIL → A architecturally incompatible → PoC 중단 (REQ-008).
 *
 * <p>실행: {@code POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3ArmEquivalencePoc*'
 *   -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)}
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V3ArmEquivalencePoc {

    /** petclinic OwnerController 분기를 여는 4-요청 시퀀스 (vanilla/pjacoco 동일). */
    private static final List<String> REQUEST_SEQUENCE = List.of(
            "lastName=",           // 모든 owner 반환 (results-found arm)
            "lastName=ZZZNONE",    // 결과 없음 (not-found arm)
            "lastName=Davis",      // 특정 성 검색 (results-found arm, 다른 결과 집합)
            "lastName=Franklin"    // 특정 성 검색 (results-found arm, 또 다른 결과)
    );

    private static final int PETCLINIC_PORT   = 8080;
    private static final int PJACOCO_CTL_PORT = 6310;   // pjacoco 제어 포트
    private static final int BOOT_TIMEOUT_S   = 90;

    @Test
    @Disabled("superseded by perRequestOtelScope_yieldsSamePartition — key equality intentionally rejected per design §5.1; baggage path drops pre-servlet filter probes")
    @DisplayName("REQ-004: per-request testId arm 등가 = vanilla coverageKey 집합과 일치 (V3(a) 게이트)")
    void perRequestTestId_yieldsSameCoverageKeySet() throws Exception {
        Path repoRoot  = Paths.get("").toAbsolutePath().getParent();
        Path workDir   = Files.createTempDirectory("v3-arm-equiv-");
        Set<String> appClasses = loadAppClasses(repoRoot);

        System.out.println("[V3a] appClasses size=" + appClasses.size());
        assertThat(appClasses).as("appClasses must not be empty").isNotEmpty();

        // ── 1. VANILLA 벡터 ──────────────────────────────────────────────
        System.out.println("[V3a] === VANILLA vector ===");
        Path vanillaWork = workDir.resolve("vanilla");
        Files.createDirectories(vanillaWork);
        JacocoAgent jacocoAgent = JacocoAgent.prepare(vanillaWork);

        Set<String> vanillaKeys = new LinkedHashSet<>();
        Process vanillaProc = launchPetclinic(repoRoot, jacocoAgent.javaToolOptions());
        try {
            CoverageClient cc = new CoverageClient("127.0.0.1", jacocoAgent.tcpPort());
            // warm-up: drain any startup coverage so per-request deltas are clean
            cc.dump(true);

            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query = REQUEST_SEQUENCE.get(i);
                hitPetclinic("127.0.0.1", PETCLINIC_PORT, query, null);
                ExecutionDataStore delta = cc.dump(true);
                String key = CoverageFingerprint.of(delta, appClasses);
                vanillaKeys.add(key);
                System.out.printf("[V3a] vanilla req-%d [%s] → key=%s%n", i, query, key);
            }
        } finally {
            stopProcess(vanillaProc, "vanilla");
        }

        System.out.println("[V3a] vanilla keys (" + vanillaKeys.size() + "): " + vanillaKeys);

        // ── 2. PJACOCO 벡터 ──────────────────────────────────────────────
        // NOTE: OTel을 함께 부착하면 pjacoco는 OTel traceId를 커버리지 키로 사용하고
        // start/stop으로 생성한 testId store는 classCount=0이 된다.
        // V3(a) 등가 비교는 baggage test.id 경로(OTel 없이 pjacoco 단독)로 검증한다.
        System.out.println("[V3a] === PJACOCO vector (pjacoco-only, no OTel — baggage test.id path) ===");
        Path pjacocoWork = workDir.resolve("pjacoco");
        Files.createDirectories(pjacocoWork);
        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();

        // pjacoco 단독 (OTel 없음): test.id baggage로 직접 testId 매핑
        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String jto = pjacocoAgent.javaToolOptions(pjacocoWork, PJACOCO_CTL_PORT, pjacocoPkg, false);

        Set<String> pjacocoKeys = new LinkedHashSet<>();
        PjacocoCoverageClient pcc = new PjacocoCoverageClient("127.0.0.1", PJACOCO_CTL_PORT, pjacocoWork);
        Process pjacocoProc = launchPetclinic(repoRoot, jto);
        try {
            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query = REQUEST_SEQUENCE.get(i);
                String testId = "eq-req-" + i;
                pcc.startTest(testId);
                hitPetclinic("127.0.0.1", PETCLINIC_PORT, query, testId);
                pcc.stopTest(testId);

                // wait for the .exec file (pjacoco flushes asynchronously after stop)
                Path execFile = pjacocoWork.resolve(testId + ".exec");
                awaitExecFile(execFile, testId);

                ExecutionDataStore store = pcc.load(testId);
                String key = CoverageFingerprint.of(store, appClasses);
                pjacocoKeys.add(key);
                System.out.printf("[V3a] pjacoco req-%d [%s] testId=%s key=%s (exec=%d bytes)%n",
                        i, query, testId, key,
                        Files.exists(execFile) ? Files.size(execFile) : -1L);
            }
        } finally {
            stopProcess(pjacocoProc, "pjacoco");
        }

        System.out.println("[V3a] pjacoco keys (" + pjacocoKeys.size() + "): " + pjacocoKeys);

        // ── 3. 등가 비교 (THE GATE) ───────────────────────────────────────
        System.out.println("[V3a] === Equivalence gate ===");
        System.out.println("[V3a] vanilla set size=" + vanillaKeys.size() + " pjacoco set size=" + pjacocoKeys.size());

        Set<String> onlyInVanilla  = new LinkedHashSet<>(vanillaKeys);
        onlyInVanilla.removeAll(pjacocoKeys);
        Set<String> onlyInPjacoco  = new LinkedHashSet<>(pjacocoKeys);
        onlyInPjacoco.removeAll(vanillaKeys);

        if (!onlyInVanilla.isEmpty() || !onlyInPjacoco.isEmpty()) {
            String msg = "V3(a) FAIL — coverageKey sets MISMATCH."
                    + "\n  vanilla size=" + vanillaKeys.size() + " pjacoco size=" + pjacocoKeys.size()
                    + "\n  only-in-vanilla (" + onlyInVanilla.size() + "): " + onlyInVanilla
                    + "\n  only-in-pjacoco (" + onlyInPjacoco.size() + "): " + onlyInPjacoco
                    + "\n  → A architecturally incompatible (per-request testId does NOT isolate arms equivalently)."
                    + "\n  → Record in spec §11 and STOP PoC.";
            System.out.println("[V3a] " + msg);
            fail(msg);
        }

        System.out.println("[V3a] V3(a) PASS — sets EQUAL, size=" + vanillaKeys.size());
        System.out.println("V3a PASS (set-size=" + vanillaKeys.size() + ")");
    }

    /**
     * REQ-004 rev.4 — partition 등가 게이트 (OTel-scope/traceId 경로).
     *
     * <p>두 벡터(vanilla tcpserver dump vs pjacoco OTel-scope traceId flush)에 동일한 입력 시퀀스를 투입해
     * 각 요청→coverageKey 매핑의 <b>partition</b>(같은 키를 공유하는 요청 인덱스 집합의 집합)이 일치하는지 검증한다.
     *
     * <p>절대 키 값 동일성은 요구하지 않는다(§5.1: OTel-scope는 JPA·async 추가 귀속으로 vanilla와 키가 다름).
     * run 내부 일관성(같은 arm→같은 키, 다른 arm→다른 키)만 성립하면 path dedup이 보존된다.
     *
     * <p>partition 불일치 → V3(a) rev.4 FAIL → A architecturally incompatible.
     */
    @Test
    @DisplayName("REQ-004: per-request arm partition 등가 (rev.4 — OTel-scope/traceId 경로, 절대 키 아님)")
    void perRequestOtelScope_yieldsSamePartition() throws Exception {
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path workDir  = Files.createTempDirectory("v3-partition-equiv-");
        Set<String> appClasses = loadAppClasses(repoRoot);

        System.out.println("[V3part] appClasses size=" + appClasses.size());
        assertThat(appClasses).as("appClasses must not be empty").isNotEmpty();

        // ── 1. VANILLA 벡터 — coverageKey per request ─────────────────────────
        System.out.println("[V3part] === VANILLA vector ===");
        Path vanillaWork = workDir.resolve("vanilla");
        Files.createDirectories(vanillaWork);
        JacocoAgent jacocoAgent = JacocoAgent.prepare(vanillaWork);

        // req index → coverageKey (insertion-ordered for stable output)
        Map<Integer, String> vanillaKeyMap = new LinkedHashMap<>();
        Process vanillaProc = launchPetclinic(repoRoot, jacocoAgent.javaToolOptions());
        try {
            CoverageClient cc = new CoverageClient("127.0.0.1", jacocoAgent.tcpPort());
            cc.dump(true); // drain startup coverage

            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query = REQUEST_SEQUENCE.get(i);
                hitPetclinic("127.0.0.1", PETCLINIC_PORT, query, null);
                ExecutionDataStore delta = cc.dump(true);
                String key = CoverageFingerprint.of(delta, appClasses);
                vanillaKeyMap.put(i, key);
                System.out.printf("[V3part] vanilla req-%d [%s] → key=%s%n", i, query, key);
            }
        } finally {
            stopProcess(vanillaProc, "vanilla");
        }
        Set<Set<Integer>> vanillaPartition = toPartition(vanillaKeyMap);
        System.out.println("[V3part] vanilla  keyMap=" + vanillaKeyMap);
        System.out.println("[V3part] vanilla  partition=" + partitionStr(vanillaKeyMap));

        // ── 2. PJACOCO OTel-scope 벡터 — traceId per request ─────────────────
        System.out.println("[V3part] === PJACOCO OTel-scope vector ===");
        Path otelWork = workDir.resolve("otel-scope");
        Files.createDirectories(otelWork);
        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar(repoRoot);

        // OTel javaagent FIRST (per design §6-2), then pjacoco with traceKeyAutoCreate=true
        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(otelWork, PJACOCO_CTL_PORT, pjacocoPkg, true);
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        PjacocoOtelScopeClient otelClient = new PjacocoOtelScopeClient("127.0.0.1", PJACOCO_CTL_PORT, otelWork);
        Map<Integer, String> otelKeyMap = new LinkedHashMap<>();
        Process otelProc = launchPetclinicWithOtel(repoRoot, combinedJto);
        try {
            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query      = REQUEST_SEQUENCE.get(i);
                String traceId    = PjacocoOtelScopeClient.traceIdFor(i);
                String traceparent = PjacocoOtelScopeClient.traceparentFor(traceId);

                hitPetclinicWithTraceparent("127.0.0.1", PETCLINIC_PORT, query, traceparent);
                otelClient.flush(traceId);

                ExecutionDataStore store = otelClient.awaitAndLoad(traceId);
                String key = CoverageFingerprint.of(store, appClasses);
                otelKeyMap.put(i, key);

                long execBytes = Files.exists(otelClient.execPath(traceId))
                        ? Files.size(otelClient.execPath(traceId)) : -1L;
                System.out.printf("[V3part] otel req-%d [%s] traceId=%.8s… → key=%s (exec=%d bytes)%n",
                        i, query, traceId, key, execBytes);
            }
        } finally {
            stopProcess(otelProc, "otel-scope");
        }
        Set<Set<Integer>> otelPartition = toPartition(otelKeyMap);
        System.out.println("[V3part] otel partition=" + partitionStr(otelKeyMap));

        // ── 3. PARTITION 비교 (THE GATE) ──────────────────────────────────────
        System.out.println("[V3part] === Partition-equivalence gate ===");
        System.out.println("[V3part] vanilla  distinct paths=" + vanillaPartition.size()
                + "  partition=" + vanillaPartition);
        System.out.println("[V3part] otel     distinct paths=" + otelPartition.size()
                + "  partition=" + otelPartition);

        // Non-triviality guard: the vanilla partition must contain at least one non-singleton
        // group (i.e., arm MERGING must be demonstrated — if all groups were singletons, a trivial
        // all-distinct fingerprinter would pass the equality check without proving dedup).
        assertThat(vanillaPartition)
                .as("vanilla partition must have at least one non-singleton group (arm merging)")
                .anyMatch(g -> g.size() > 1);

        if (!vanillaPartition.equals(otelPartition)) {
            String msg = "V3(a) rev.4 FAIL — PARTITION MISMATCH."
                    + "\n  vanilla  partition=" + vanillaPartition
                    + "\n  otel     partition=" + otelPartition
                    + "\n  → OTel-scope arm grouping differs from vanilla."
                    + "\n  → A architecturally incompatible (partition 불일치). Record in §11 and STOP PoC.";
            System.out.println("[V3part] " + msg);
            fail(msg);
        }

        System.out.println("[V3part] V3(a) rev.4 PASS — PARTITION MATCH, distinct-paths="
                + vanillaPartition.size() + " partition=" + vanillaPartition);
        System.out.println("V3a-rev4 PARTITION MATCH partition=" + vanillaPartition);
    }

    /**
     * Computes the partition of requests: groups request indices that share the same coverageKey.
     * Returns a Set of Sets of indices (each inner set = one equivalence class / distinct path).
     */
    private static Set<Set<Integer>> toPartition(Map<Integer, String> keyMap) {
        // key → set of request indices
        Map<String, Set<Integer>> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : keyMap.entrySet()) {
            groups.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
        }
        return new LinkedHashSet<>(groups.values());
    }

    /** Readable partition string: e.g. {{0,2},{1},{3}} */
    private static String partitionStr(Map<Integer, String> keyMap) {
        Set<Set<Integer>> partition = toPartition(keyMap);
        return partition.stream()
                .map(g -> "{" + g.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}")
                .collect(Collectors.joining(",", "{", "}"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** petclinic classfiles ディレクトリから appClasses (internal form, petclinic pkg 限定) を収集. */
    private Set<String> loadAppClasses(Path repoRoot) throws Exception {
        // use lib-launch-petclinic.sh convention: petclinic classfiles = PETCLINIC_DIR/build/classes/java/main
        String petclinicDir = System.getenv().getOrDefault("PETCLINIC_DIR",
                System.getProperty("user.home") + "/github_spring-petclinic/spring-petclinic");
        Path classesRoot = Paths.get(petclinicDir, "build/classes/java/main");

        // trigger build first so classfiles exist
        buildPetclinic(Paths.get(petclinicDir));

        assertThat(classesRoot).as("petclinic classfiles dir must exist after build").isDirectory();

        // collect all .class files, convert to internal class names
        return Files.walk(classesRoot)
                .filter(p -> p.toString().endsWith(".class"))
                .map(p -> classesRoot.relativize(p).toString()
                        .replace(File.separatorChar, '/')
                        .replaceAll("\\.class$", ""))
                .filter(name -> name.startsWith("org/springframework/samples/petclinic/"))
                .collect(Collectors.toSet());
    }

    /** Gradle bootJar を実行してビルドを確保する. PETCLINIC_JAVA (JDK 17) を JAVA_HOME に設定して起動. */
    private void buildPetclinic(Path petclinicDir) throws Exception {
        System.out.println("[V3a] building petclinic (./gradlew bootJar)...");
        ProcessBuilder pb = new ProcessBuilder("./gradlew", "bootJar", "-q")
                .directory(petclinicDir.toFile())
                .redirectErrorStream(true);
        // petclinic 4.x requires JDK 17; inherit PETCLINIC_JAVA if set (same as lib-launch-petclinic.sh)
        String petclinicJava = System.getenv("PETCLINIC_JAVA");
        if (petclinicJava != null && !petclinicJava.isBlank()) {
            pb.environment().put("JAVA_HOME", petclinicJava);
        } else {
            // try macOS java_home -v 17 fallback
            String java17Home = detectJava17Home();
            if (java17Home != null) pb.environment().put("JAVA_HOME", java17Home);
        }
        Process p = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            out = r.lines().collect(Collectors.joining("\n"));
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("petclinic build failed (exit " + code + "):\n" + out);
        }
        System.out.println("[V3a] petclinic build OK");
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

    private Path resolvePetclinicJar(Path repoRoot) throws Exception {
        String petclinicDir = System.getenv().getOrDefault("PETCLINIC_DIR",
                System.getProperty("user.home") + "/github_spring-petclinic/spring-petclinic");
        Path libsDir = Paths.get(petclinicDir, "build/libs");
        return Files.list(libsDir)
                .filter(p -> p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().contains("-plain"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("petclinic fat jar not found in " + libsDir));
    }

    private Path resolveOtelJar(Path repoRoot) {
        // same path as v1-agent-coexistence.sh
        String otelJar = System.getenv().getOrDefault("OTEL_JAR",
                System.getProperty("user.home")
                        + "/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar");
        Path path = Paths.get(otelJar);
        assertThat(path).as("OTel jar must exist (set OTEL_JAR env)").isRegularFile();
        return path;
    }

    /** petclinic を起動し readiness を待つ. */
    private Process launchPetclinic(Path repoRoot, String javaToolOptions) throws Exception {
        Path petclinicJar = resolvePetclinicJar(repoRoot);
        String javaHome = System.getenv("PETCLINIC_JAVA");
        String javaBin = (javaHome != null && !javaHome.isBlank())
                ? javaHome + "/bin/java"
                : resolveJava17();

        System.out.println("[V3a] launching petclinic: " + petclinicJar);
        System.out.println("[V3a] JAVA_TOOL_OPTIONS=" + javaToolOptions);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v3-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        Process proc = pb.start();

        System.out.println("[V3a] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V3a] petclinic up");
        return proc;
    }

    private String resolveJava17() {
        String petclinicJava = System.getenv("PETCLINIC_JAVA");
        if (petclinicJava != null && !petclinicJava.isBlank()) return petclinicJava + "/bin/java";
        String java17Home = detectJava17Home();
        if (java17Home != null) return java17Home + "/bin/java";
        return "java";
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
            } catch (Exception ignored) { /* not ready yet */ }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("petclinic did not come up in " + BOOT_TIMEOUT_S + "s");
    }

    /** Launches petclinic with OTel + pjacoco agents and disables external OTLP export. */
    private Process launchPetclinicWithOtel(Path repoRoot, String javaToolOptions) throws Exception {
        Path petclinicJar = resolvePetclinicJar(repoRoot);
        String javaHome = System.getenv("PETCLINIC_JAVA");
        String javaBin = (javaHome != null && !javaHome.isBlank())
                ? javaHome + "/bin/java"
                : resolveJava17();

        System.out.println("[V3part] launching petclinic (OTel+pjacoco): " + petclinicJar);
        System.out.println("[V3part] JAVA_TOOL_OPTIONS=" + javaToolOptions);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v3part-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-v3part-probe");

        Process proc = pb.start();
        System.out.println("[V3part] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V3part] petclinic up");
        return proc;
    }

    private void hitPetclinic(String host, int port, String query, String testId) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/owners?" + query))
                .GET();
        if (testId != null) {
            req.header("baggage", "test.id=" + testId);
        }
        HttpResponse<Void> r = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic returned " + r.statusCode() + " for " + query);
        }
    }

    private void hitPetclinicWithTraceparent(String host, int port, String query,
                                             String traceparent) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/owners?" + query))
                .header("traceparent", traceparent)
                .GET()
                .build();
        HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic returned " + r.statusCode() + " for " + query);
        }
    }

    /** Wait up to 5s for .exec file to appear (pjacoco async flush). */
    private void awaitExecFile(Path execFile, String testId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(execFile) && Files.size(execFile) > 0) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("pjacoco .exec not produced for testId=" + testId
                + " at " + execFile);
    }

    private void stopProcess(Process proc, String label) {
        if (proc.isAlive()) {
            System.out.println("[V3a] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
