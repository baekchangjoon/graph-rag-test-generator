package io.graphrag.builder.poc.fanout;

import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.CoverageFingerprint;
import io.graphrag.builder.coverage.JacocoAgent;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Investigation: V3(b) OTel-scope traceId path — JwtAuthenticationFilter probe capture 가설 검증.
 *
 * <p>Task 4 결과: pjacoco baggage 경로는 pre-servlet {@code JwtAuthenticationFilter} probes(4개)를
 * drop해 vanilla와 일치하지 않았다 (REQ-004 FAIL).
 *
 * <p>가설: OTel servlet 계측은 Spring Security filter chain 앞에서 span을 시작하므로,
 * {@code ThreadLocalContextStorage#attach}가 JwtAuthenticationFilter 실행 전에 호출된다.
 * 따라서 OTel traceId scope 경로는 해당 4개 probe를 캡처할 수 있다.
 *
 * <p>검증 방법:
 * <ol>
 *   <li>petclinic을 OTel javaagent + pjacoco(traceKeyAutoCreate=true)로 기동</li>
 *   <li>각 요청에 고유 W3C traceparent 헤더 전송 → pjacoco가 traceId를 coverage key로 사용</li>
 *   <li>pjacoco control port로 traceId 기반 flush: POST /__coverage__/test/stop?testId=&lt;traceId&gt;</li>
 *   <li>flush된 .exec 파일 → CoverageFingerprint → coverageKey 수집</li>
 *   <li>vanilla 벡터(JaCoCo tcpserver)와 비교: JwtAuthenticationFilter probe 포함 여부 + key 일치</li>
 * </ol>
 *
 * <p>실행:
 * {@code POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OtelScopeEquivalenceProbe*'
 *   -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh | tail -1)}
 */
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V3OtelScopeEquivalenceProbe {

    private static final List<String> REQUEST_SEQUENCE = List.of(
            "lastName=",           // results-found arm (모든 owner 반환)
            "lastName=ZZZNONE",    // not-found arm
            "lastName=Davis",      // results-found arm (특정 성)
            "lastName=Franklin"    // results-found arm (또 다른 성)
    );

    private static final int PETCLINIC_PORT   = 8080;
    private static final int VANILLA_TCP_PORT = 6300;
    private static final int PJACOCO_CTL_PORT = 6310;
    private static final int BOOT_TIMEOUT_S   = 90;

    /** JwtAuthenticationFilter 클래스명 (internal form). */
    private static final String JWT_FILTER_CLASS =
            "org/springframework/samples/petclinic/security/JwtAuthenticationFilter";

    @Test
    @DisplayName("V3(b) 조사: OTel-scope traceId 경로가 JwtAuthenticationFilter probe를 캡처하는가")
    void otelScope_traceIdPath_capturesFilterProbes() throws Exception {
        Path repoRoot = Paths.get("").toAbsolutePath().getParent();
        Path workDir = Files.createTempDirectory("v3-otel-scope-probe-");
        Set<String> appClasses = loadAppClasses(repoRoot);

        System.out.println("[V3otel] appClasses size=" + appClasses.size());
        assertThat(appClasses).as("appClasses must not be empty").isNotEmpty();

        // ── 1. VANILLA 벡터 (기준선) ─────────────────────────────────────────
        System.out.println("[V3otel] === VANILLA vector ===");
        Path vanillaWork = workDir.resolve("vanilla");
        Files.createDirectories(vanillaWork);
        JacocoAgent jacocoAgent = JacocoAgent.prepare(vanillaWork);

        Set<String> vanillaKeys = new LinkedHashSet<>();
        boolean vanillaHasJwtFilter = false;
        Process vanillaProc = launchPetclinic(repoRoot, jacocoAgent.javaToolOptions(), null);
        try {
            CoverageClient cc = new CoverageClient("127.0.0.1", jacocoAgent.tcpPort());
            cc.dump(true); // warm-up drain

            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query = REQUEST_SEQUENCE.get(i);
                hitPetclinic("127.0.0.1", PETCLINIC_PORT, query, null, null);
                ExecutionDataStore delta = cc.dump(true);
                String key = CoverageFingerprint.of(delta, appClasses);
                vanillaKeys.add(key);
                boolean hasFilter = delta.contains(JWT_FILTER_CLASS);
                if (hasFilter) vanillaHasJwtFilter = true;
                System.out.printf("[V3otel] vanilla req-%d [%s] → key=%s  jwtFilter=%s%n",
                        i, query, key, hasFilter ? "YES" : "no");
            }
        } finally {
            stopProcess(vanillaProc, "vanilla");
        }
        System.out.println("[V3otel] vanilla keys (" + vanillaKeys.size() + "): " + vanillaKeys);
        System.out.println("[V3otel] vanilla JwtAuthenticationFilter present: " + vanillaHasJwtFilter);

        // ── 2. OTel-scope 벡터 (traceId path) ───────────────────────────────
        System.out.println("[V3otel] === OTel-scope traceId vector ===");
        Path otelWork = workDir.resolve("otel-scope");
        Files.createDirectories(otelWork);
        PjacocoAgent pjacocoAgent = PjacocoAgent.fromSystemProperty();
        Path otelJar = resolveOtelJar(repoRoot);

        // pjacoco: traceKeyAutoCreate=true + OTel javaagent 공존 → OTel traceId가 coverage key
        String pjacocoPkg = "org.springframework.samples.petclinic.*";
        String pjacocoJto = pjacocoAgent.javaToolOptions(otelWork, PJACOCO_CTL_PORT, pjacocoPkg, true);
        // OTel javaagent를 앞에 붙임 (pjacoco가 discoverOtelJar로 찾음)
        String combinedJto = "-javaagent:" + otelJar.toAbsolutePath() + " " + pjacocoJto;

        Set<String> otelScopeKeys = new LinkedHashSet<>();
        boolean otelScopeHasJwtFilter = false;
        boolean anyIncompleteAttribution = false;

        PjacocoCoverageClient pcc = new PjacocoCoverageClient("127.0.0.1", PJACOCO_CTL_PORT, otelWork);
        Process otelProc = launchPetclinic(repoRoot, combinedJto, null);
        try {
            for (int i = 0; i < REQUEST_SEQUENCE.size(); i++) {
                String query = REQUEST_SEQUENCE.get(i);
                // 고유 32-hex traceId 생성 (W3C traceparent format)
                String traceId = String.format("%032x", (long) i * 0x1000000000000001L + 0xABCDEF0123456789L);
                String traceparent = "00-" + traceId + "-0000000000000001-01";

                // traceId를 가진 요청 전송 → pjacoco가 OTel scope 이벤트에서 traceId를 key로 store 생성
                hitPetclinic("127.0.0.1", PETCLINIC_PORT, query, null, traceparent);

                // flush: traceId를 testId로 사용
                pcc.stopTest(traceId);

                // exec 파일 대기
                Path execFile = otelWork.resolve(traceId + ".exec");
                boolean execProduced = awaitExecFile(execFile, traceId, 5);

                if (!execProduced) {
                    System.out.printf("[V3otel] WARN: .exec not produced for traceId=%s — classCount may be 0%n", traceId);
                    System.out.printf("[V3otel] otel req-%d [%s] traceId=%s → exec=MISSING (possible empty store)%n",
                            i, query, traceId);
                    // OTel traceId store가 없으면 pjacoco가 exec를 생성하지 않거나 32-byte empty만 씀
                    // → 이 경우도 데이터로 기록하고 계속 진행
                    otelScopeKeys.add("EXEC_MISSING_" + i);
                    continue;
                }

                ExecutionDataStore store = pcc.load(traceId);
                String key = CoverageFingerprint.of(store, appClasses);
                otelScopeKeys.add(key);

                boolean hasFilter = store.contains(JWT_FILTER_CLASS);
                if (hasFilter) otelScopeHasJwtFilter = true;

                long execSize = Files.exists(execFile) ? Files.size(execFile) : -1L;
                System.out.printf("[V3otel] otel req-%d [%s] traceId=%.8s… → key=%s  jwtFilter=%s  exec=%d bytes%n",
                        i, query, traceId, key, hasFilter ? "YES" : "no", execSize);

                // incompleteAttribution 확인 (JSON 메타가 없으면 추론 불가; exec 크기로 간접 확인)
                // pjacoco가 JSON 메타 엔드포인트를 제공하면 직접 조회
                checkIncompleteAttribution(traceId);
            }
        } finally {
            stopProcess(otelProc, "otel-scope");
        }

        // ── 3. 결과 출력 ─────────────────────────────────────────────────────
        System.out.println("\n[V3otel] === RESULT SUMMARY ===");
        System.out.println("[V3otel] vanilla  set size=" + vanillaKeys.size() + " keys=" + vanillaKeys);
        System.out.println("[V3otel] otelScope set size=" + otelScopeKeys.size() + " keys=" + otelScopeKeys);

        Set<String> intersection = new LinkedHashSet<>(vanillaKeys);
        intersection.retainAll(otelScopeKeys);
        Set<String> onlyInVanilla = new LinkedHashSet<>(vanillaKeys);
        onlyInVanilla.removeAll(otelScopeKeys);
        Set<String> onlyInOtel = new LinkedHashSet<>(otelScopeKeys);
        onlyInOtel.removeAll(vanillaKeys);

        System.out.println("[V3otel] intersection size=" + intersection.size());
        System.out.println("[V3otel] only-in-vanilla (" + onlyInVanilla.size() + "): " + onlyInVanilla);
        System.out.println("[V3otel] only-in-otelScope (" + onlyInOtel.size() + "): " + onlyInOtel);
        System.out.println("[V3otel] vanilla  JwtAuthenticationFilter captured: " + vanillaHasJwtFilter);
        System.out.println("[V3otel] otelScope JwtAuthenticationFilter captured: " + otelScopeHasJwtFilter);

        if (intersection.size() == vanillaKeys.size() && onlyInVanilla.isEmpty() && onlyInOtel.isEmpty()) {
            System.out.println("[V3otel] VERDICT: HYPOTHESIS CONFIRMED — sets EQUAL, filter probes captured");
        } else if (otelScopeHasJwtFilter) {
            System.out.println("[V3otel] VERDICT: PARTIAL — OTel scope captures JwtFilter probes, but keys still mismatch");
        } else {
            System.out.println("[V3otel] VERDICT: HYPOTHESIS REFUTED — OTel scope does NOT capture JwtFilter probes");
        }

        // assertion: 이 조사는 FAIL을 강요하지 않는다 — 결과를 기록하는 것이 목적.
        // 단, 가설 검증을 위한 최소 sanity: vanilla에 JwtFilter가 있어야 기준선이 유효.
        assertThat(vanillaHasJwtFilter)
                .as("vanilla vector must include JwtAuthenticationFilter probes (baseline validity)")
                .isTrue();

        // OTel exec가 전혀 생산되지 않으면 환경 오류
        long validOtelKeys = otelScopeKeys.stream().filter(k -> !k.startsWith("EXEC_MISSING")).count();
        assertThat(validOtelKeys)
                .as("At least one OTel-scope exec must be produced to assess the hypothesis")
                .isGreaterThan(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Set<String> loadAppClasses(Path repoRoot) throws Exception {
        String petclinicDir = System.getenv().getOrDefault("PETCLINIC_DIR",
                System.getProperty("user.home") + "/github_spring-petclinic/spring-petclinic");
        Path classesRoot = Paths.get(petclinicDir, "build/classes/java/main");
        buildPetclinic(Paths.get(petclinicDir));
        assertThat(classesRoot).as("petclinic classfiles dir must exist after build").isDirectory();
        return Files.walk(classesRoot)
                .filter(p -> p.toString().endsWith(".class"))
                .map(p -> classesRoot.relativize(p).toString()
                        .replace(File.separatorChar, '/')
                        .replaceAll("\\.class$", ""))
                .filter(name -> name.startsWith("org/springframework/samples/petclinic/"))
                .collect(Collectors.toSet());
    }

    private void buildPetclinic(Path petclinicDir) throws Exception {
        System.out.println("[V3otel] building petclinic (./gradlew bootJar)...");
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
        System.out.println("[V3otel] petclinic build OK");
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
        } catch (Exception ignored) {}
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
        String otelJar = System.getenv().getOrDefault("OTEL_JAR",
                System.getProperty("user.home")
                        + "/github_tainted-spring/tainted-spring-platform/jacoco/opentelemetry-javaagent.jar");
        Path path = Paths.get(otelJar);
        assertThat(path).as("OTel jar must exist (set OTEL_JAR env)").isRegularFile();
        return path;
    }

    private Process launchPetclinic(Path repoRoot, String javaToolOptions, String extraEnv) throws Exception {
        Path petclinicJar = resolvePetclinicJar(repoRoot);
        String javaHome = System.getenv("PETCLINIC_JAVA");
        String javaBin = (javaHome != null && !javaHome.isBlank())
                ? javaHome + "/bin/java"
                : resolveJava17();

        System.out.println("[V3otel] launching petclinic: " + petclinicJar);
        System.out.println("[V3otel] JAVA_TOOL_OPTIONS=" + javaToolOptions);

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", petclinicJar.toString(),
                "--server.port=" + PETCLINIC_PORT,
                "--spring.datasource.url=jdbc:h2:mem:testdb")
                .redirectErrorStream(true)
                .redirectOutput(new File("/tmp/petclinic-v3otel-stdout.log"));
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions);
        // OTel OTLP export を無効化 — 外部 collector なし
        pb.environment().put("OTEL_METRICS_EXPORTER", "none");
        pb.environment().put("OTEL_TRACES_EXPORTER", "none");
        pb.environment().put("OTEL_LOGS_EXPORTER", "none");
        pb.environment().put("OTEL_SERVICE_NAME", "petclinic-v3otel-probe");

        Process proc = pb.start();
        System.out.println("[V3otel] PID=" + proc.pid() + ", waiting for :" + PETCLINIC_PORT + "...");
        awaitPort(PETCLINIC_PORT, proc);
        System.out.println("[V3otel] petclinic up");
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
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new IllegalStateException("petclinic did not come up in " + BOOT_TIMEOUT_S + "s");
    }

    private void hitPetclinic(String host, int port, String query,
                               String testId, String traceparent) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/owners?" + query))
                .GET();
        if (testId != null) req.header("baggage", "test.id=" + testId);
        if (traceparent != null) req.header("traceparent", traceparent);
        HttpResponse<Void> r = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() >= 500) {
            throw new IllegalStateException("petclinic returned " + r.statusCode() + " for " + query);
        }
    }

    /** @return true if exec file appeared within timeoutSeconds */
    private boolean awaitExecFile(Path execFile, String traceId, int timeoutSeconds) throws Exception {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(execFile) && Files.size(execFile) > 32) return true;
            Thread.sleep(300);
        }
        return false;
    }

    /** pjacoco JSON 메타 엔드포인트 조회 (incompleteAttribution 플래그 확인). */
    private void checkIncompleteAttribution(String traceId) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + PJACOCO_CTL_PORT
                                    + "/__coverage__/test/" + traceId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                String body = r.body();
                boolean incomplete = body.contains("\"incompleteAttribution\":true");
                int dropped = extractInt(body, "droppedProbes");
                System.out.printf("[V3otel] pjacoco JSON for %s: incompleteAttribution=%s droppedProbes=%d%n",
                        traceId, incomplete, dropped);
            } else {
                System.out.printf("[V3otel] pjacoco JSON endpoint status=%d for %s%n", r.statusCode(), traceId);
            }
        } catch (Exception e) {
            System.out.printf("[V3otel] pjacoco JSON check failed for %s: %s%n", traceId, e.getMessage());
        }
    }

    private int extractInt(String json, String key) {
        try {
            String marker = "\"" + key + "\":";
            int idx = json.indexOf(marker);
            if (idx < 0) return -1;
            int start = idx + marker.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private void stopProcess(Process proc, String label) {
        if (proc.isAlive()) {
            System.out.println("[V3otel] stopping " + label + " PID=" + proc.pid());
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
