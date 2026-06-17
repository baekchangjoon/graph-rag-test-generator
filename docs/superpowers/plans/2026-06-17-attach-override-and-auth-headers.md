# Attach-via-Override-Compose + Custom Auth Headers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add (A) an *attach mode* where the builder analyzes a SUT brought up from the **user's own docker-compose** plus a builder-generated **override compose** (instead of Testcontainers + a local `java -jar` process), and (B) **custom request headers** (incl. a per-request `{{now:...}}` timestamp in Asia/Seoul) injected on every REST call and optionally on the auth-login call.

**Architecture:**
- **Part A:** Extract the SUT-interaction surface the exploration loop depends on (`SutHandle`) and the environment surface `build()` depends on (`ExplorationEnvironment`). `AnalysisEnvironment` (Testcontainers) and a new `AttachedComposeEnvironment` (docker compose) both implement it. The exploration loop in `BuilderCli.build()` is extracted into a shared `explore(...)` method so both modes reuse it unchanged. Attach mode captures SQL by injecting Hibernate/MyBatis logging env + JaCoCo/OTEL agents via a generated override compose, streaming `docker compose logs` to a file that the existing byte-offset slicer reads.
- **Part B:** A `HeaderTemplate` resolver expands `{{now:<java-time-pattern>}}` (zone `Asia/Seoul`, evaluated per request) while preserving surrounding literals. `EndpointExplorationRunner.doSend` and (optionally) `AuthTokenProvider.login` apply the resolved headers.

**Tech Stack:** Java 17, Gradle, Testcontainers, JaCoCo TCP agent, OpenTelemetry javaagent, Jackson YAML, `java.net.http.HttpClient`, JUnit 5, Docker / `docker compose` v2.

---

## v2 — 3-model review triage (2026-06-17)

This plan was reviewed by Claude Sonnet, Gemini 3.5 Flash, and GPT-5.5. Dispositions:

**Accepted → folded inline below:**
- **[CRITICAL, Sonnet+Gemini] `--sut-jar` required in attach mode.** Without the jar, `BranchCoverageAnalyzer(sutJar)` and `InputOracle.SutCode(sutSrc, sutJar)` NPE; worse, `analyzer.appClassNames()` is empty → `CoverageFingerprint.of(delta, appClasses)` returns the same key for every request → all paths collapse and exploration is meaningless. → attach mode **requires `--sut-jar`** (Task A7, A8).
- **[CRITICAL, all 3] App readiness.** `e2e/docker-compose.yml`'s `app` has no healthcheck, so `up --wait` returns before Spring is ready. → `AttachedComposeEnvironment.start()` **polls `<appBaseUri><health-path>` until `UP`** (default `/actuator/health`, `--ready-timeout` 120s), mirroring `SutProcess.awaitHealthy` (Task A6).
- **[CRITICAL, GPT] Custom headers must reach generated tests.** User decision: **extend to generated tests.** Implemented env-driven (consistent with existing `AUTH_*`): testlib `RestAssuredHelper` reads `REQUEST_HEADERS`/`REQUEST_HEADERS_ON_LOGIN` and applies them per request using a shared `HeaderTemplate`. No `GraphAsset`/generator-template change (headers are global runtime config, not per-endpoint graph facts). → `HeaderTemplate` moves to **`shared-model`** (Task B1); new testlib task (B5) + full-pipeline E2E (B6).
- **[important] `BuildConfig`/`AttachConfig` not in file list; `new BuildConfig(...)` call-sites.** Added to File Structure; `BuildConfig` gains a **compact-constructor default** (`attach=null`, `requestHeaders=empty`) so existing `new BuildConfig(...)` in `BuilderE2eTest` keep compiling (Task A7/B3 note).
- **[important, Sonnet] OTEL env vars missing from override.** `OverrideComposeGenerator.Spec` gains `extraEnv`; `runAttached` passes `otel.env(sutId)` (Task A4/A7).
- **[important, Sonnet] `tables` extraction placement** specified in `explore()` (Task A7).
- **[important, GPT] A-E2E** gains `--sut-id order` (project-name match), `--sut-jar`, an explicit `docker compose build app`, and a post-teardown "no containers" assertion; base reference corrected to `e2e/run-e2e.sh` (Task A8).
- **[important, GPT] `--sut-service` vs `--app-service`** — standardized on `--app-service` (stray `--sut-service` removed).
- **[important, Gemini] Kafka skip log** — `explore()` logs a warning when consumers exist but `kafkaBootstrap == null` (Task A7).
- **[recommended] fail-fast `{{now:pattern}}` validation** in `RequestHeaders.parse` (Task B2); **YAML round-trip assertion** + **`SPRING_APPLICATION_JSON` replacement** documented as a v1 limitation (Task A4/C1); **`jdk.httpserver` add-exports** note (Task B4); AuthTokenProvider login-timestamp note (Task B3).

**Rejected:**
- **[Gemini I6] `OTelAgent` typo** — the plan already uses `OtelAgent` (lowercase t). Misread; no change.
- **[Sonnet I7 / GPT — full `SPRING_APPLICATION_JSON` merge]** — reading + deep-merging the user compose's existing `SPRING_APPLICATION_JSON` is deferred; v1 documents it as a limitation instead (a SUT that injects app config via `SPRING_APPLICATION_JSON` is unsupported in attach v1). Rationale: merge adds parse-and-reconcile complexity for a case the sample SUTs don't hit; explicit limitation is the proportional v1 call. A `--db-type` bypass is likewise deferred — `ComposeInspector` already requires a recognizable DB image, same as analysis mode; documented in C1.

---

## Scope & Non-Goals

**In scope (attach mode v1):** Postgres/MySQL/MariaDB SUTs; HTTP endpoint exploration; SQL+bind capture via injected logging; JaCoCo branch coverage; JWT auth; WebSocket exploration (free — same published app port). The builder **owns the lifecycle**: it runs `docker compose up` from the user's compose + override, then `down` (decision A from design discussion — fresh stack per run).

**Out of scope v1 (documented limitations, not silently dropped):**
- **Kafka capture in attach mode** — requires a host-reachable external bootstrap; gated behind an explicit `--kafka-bootstrap host:port`. If absent, Kafka consumers are skipped with a logged notice.
- **Embedded outbound-HTTP capture** (`HttpCaptureServer`) in attach mode — the SUT runs in a container and cannot reach the builder's host WireMock by default. v1 returns no captured outbound calls in attach mode (the runner already tolerates `httpCapture == null`). Documented in the guide.
- **Attaching to an already-running long-lived stack** (decision B) — not v1; override requires `up` (container recreation).

## E2E / Acceptance tests (definition of done)

- **A-E2E (`e2e/run-attach-e2e.sh`):** order-service brought up via `e2e/docker-compose.yml` + generated override; builder runs in attach mode (with `--sut-jar` + `--sut-id order`); `graph.json` contains the same core order/booking endpoints and non-empty `sql` with bind values, and `exploration-report.json` shows non-zero covered app branches. Teardown asserts no containers remain for the project.
- **B-E2E-1 (`HeaderTemplateHttpIT`):** an in-JVM `com.sun.net.httpserver.HttpServer` asserts the inbound `X-AuthorizationTime` matches `^\d{14}0900$` and is within a freshness window of server now; a real `HttpClient` request built through the header-injection path gets `200`; a request without the header gets `401`. (Fast, isolated check of the builder-side injection + resolver.)
- **B-E2E-2 (full pipeline, `e2e/run-auth-headers-e2e.sh`):** order-service runs with an **env-gated** interceptor (`REQUIRE_AUTH_TIME=true`, default off so existing E2E is unaffected) that enforces a fresh `X-AuthorizationTime` on all endpoints; the builder explores with `--request-headers-file`, the generator emits tests, and the generated tests run with `REQUEST_HEADERS` set in the test environment → all pass. Proves the header reaches **both** builder exploration and generated-test re-execution (the GPT-I1 gap).
- **Regression:** `./gradlew check` green; existing `e2e/run-e2e.sh` (analysis mode, no `REQUIRE_AUTH_TIME`) still green (proves the `SutHandle`/`explore()` refactor is behavior-preserving and that the interceptor is inert by default).

---

## File Structure

**Part A — attach mode**
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutHandle.java` — interface: the SUT-interaction surface runners use.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutProcess.java` — `implements SutHandle`.
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/env/ContainerSut.java` — `SutHandle` over a streamed `docker compose logs` file.
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/env/ExplorationEnvironment.java` — interface `build()` depends on.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java` — `implements ExplorationEnvironment` (+ `coverageHost()/coveragePort()`, own the JaCoCo agent).
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java` — builds the override YAML string.
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java` — compose up/down, port resolution, log stream, `ExplorationEnvironment`.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/JacocoAgent.java` — add a container-reachable (`address=*`) options variant.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/run/{EndpointExplorationRunner,KafkaCaptureRunner,WsCaptureRunner}.java` — ctor param `SutProcess` → `SutHandle`.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — extract `explore(...)`; add `AttachConfig` record + new flags (`--attach`/`--app-service`/`--app-port`/`--app-container-port`/`--jacoco-port`/`--jdbc-url`/`--kafka-bootstrap`/`--health-path`/`--ready-timeout`); `--sut-jar` required in attach too; mode selection. **`BuildConfig` record** in this file gains an `AttachConfig attach` field (+ `RequestHeaders requestHeaders` from Part B) with a **compact-constructor / overload default** so existing `new BuildConfig(...)` call-sites compile.
- Create `e2e/run-attach-e2e.sh`.

**Part B — auth headers**
- Create `shared-model/src/main/java/io/graphrag/model/HeaderTemplate.java` — `{{now:<pattern>}}` resolver (Asia/Seoul), **shared** by builder and testlib (both already depend on `shared-model`).
- Create `graph-rag-builder/src/main/java/io/graphrag/builder/run/RequestHeaders.java` — parsed header set + `onLogin` flag + `resolved(Instant)` (uses `io.graphrag.model.HeaderTemplate`).
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — apply headers in `doSend`.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/run/AuthTokenProvider.java` — apply headers on login when `onLogin`.
- Modify `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` — `--request-headers-file`/`--request-headers-on-login`; wire into runner + AuthTokenProvider; thread `requestHeaders` through `BuildConfig`.
- Update `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderE2eTest.java` and any other `new BuildConfig(...)` / `new AuthTokenProvider(...)` call-sites (run `rg "new BuildConfig\(|new AuthTokenProvider\("` first).
- Modify `testlib/src/main/java/io/graphrag/testlib/api/RestAssuredHelper.java` — read env `REQUEST_HEADERS` (lines `Name: template`) + `REQUEST_HEADERS_ON_LOGIN`; apply resolved headers (`io.graphrag.model.HeaderTemplate`) on every request and on the auth/login call when on-login. Wire from `TestScope.create(Env)`.
- Modify `samples/order-service` — add an **env-gated** (`REQUIRE_AUTH_TIME`) Spring interceptor enforcing a fresh `X-AuthorizationTime` (`yyyyMMddHHmmss`+`0900`, Asia/Seoul, ±N min); inert by default.
- Create `e2e/run-auth-headers-e2e.sh` (full-pipeline B-E2E-2).

**Part C — docs**
- Create `docs/26-attach-mode.md` — user guide for generating/using the override compose.
- Modify `README.md` — link the guide; add attach-mode invocation example.
- Modify `docs/README.md` — add doc to the map.
- Modify `docs/03-graph-rag-builder.md` — short attach-mode subsection cross-link.

---

# PART A — Attach via Override Compose

### Task A1: Extract `SutHandle` interface (behavior-preserving refactor)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutHandle.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutProcess.java` (class declaration only)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:76,94`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureRunner.java:54,60`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/WsCaptureRunner.java:47,52`

- [ ] **Step 1: Create the interface**

```java
package io.graphrag.builder.env;

/** SUT 상호작용 표면(탐색 루프가 의존): base URL + SQL 캡처용 로그 슬라이스. SutProcess(분석)와 ContainerSut(attach) 공통. */
public interface SutHandle {
    String baseUri();
    long logOffset();
    String readLog();
    String readLogFrom(long offset);
    String readLogRange(long start, long end);
    void stop();
}
```

- [ ] **Step 2: `SutProcess implements SutHandle`**

In `SutProcess.java`, change `public final class SutProcess {` → `public final class SutProcess implements SutHandle {`. All five methods already exist with matching signatures — no body changes.

- [ ] **Step 3: Widen runner ctor params**

In each of the three runners, change the field type and ctor param `SutProcess sut` → `SutHandle sut` (import `io.graphrag.builder.env.SutHandle`). No call-site (`BuilderCli`) change needed — `SutProcess` is a `SutHandle`.

- [ ] **Step 4: Compile + run builder unit tests**

Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:test`
Expected: PASS (pure type widening; `SutProcessLogSliceTest` still green).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/SutHandle.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/env/SutProcess.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/run/
git commit -m "refactor(builder): extract SutHandle interface from SutProcess"
```

---

### Task A2: `ContainerSut` — SutHandle over a streamed docker log file

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/ContainerSut.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/ContainerSutTest.java`

`ContainerSut` reuses `SutProcess.sliceUtf8` (package-private static, same `env` package — already covered by `SutProcessLogSliceTest`). The log file is appended to by a `docker compose logs --no-log-prefix -f <app-service>` process that the *environment* (Task A6) starts; `ContainerSut` only reads it.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSutTest {
    @Test
    void readsLogRangeByByteOffsetOverFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "org.hibernate.SQL : select 1\n");
        ContainerSut sut = new ContainerSut("http://localhost:18080", log, null);
        long off = sut.logOffset();
        assertEquals(Files.size(log), off);
        Files.writeString(log, "binding parameter (1:INT) <- [7]\n",
                java.nio.file.StandardOpenOption.APPEND);
        String range = sut.readLogRange(off, sut.logOffset());
        assertTrue(range.contains("binding parameter (1:INT) <- [7]"));
        assertEquals("http://localhost:18080", sut.baseUri());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*ContainerSutTest'`
Expected: FAIL (`ContainerSut` does not exist).

- [ ] **Step 3: Implement `ContainerSut`**

```java
package io.graphrag.builder.env;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** attach 모드 SutHandle: 컨테이너 app의 published URL + `docker compose logs`가 append하는 로그 파일의 byte 슬라이스. */
public final class ContainerSut implements SutHandle {

    private final String baseUri;
    private final Path logFile;
    private final Process logTail;   // nullable (테스트). 환경이 소유한 `docker compose logs -f` 프로세스.

    public ContainerSut(String baseUri, Path logFile, Process logTail) {
        this.baseUri = baseUri;
        this.logFile = logFile;
        this.logTail = logTail;
    }

    @Override public String baseUri() { return baseUri; }

    @Override public long logOffset() {
        try { return Files.exists(logFile) ? Files.size(logFile) : 0; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public String readLog() { return readLogRange(0, Long.MAX_VALUE); }
    @Override public String readLogFrom(long offset) { return readLogRange(offset, Long.MAX_VALUE); }

    @Override public String readLogRange(long start, long end) {
        return SutProcess.sliceUtf8(readBytes(), start, end);
    }

    private byte[] readBytes() {
        try { return Files.exists(logFile) ? Files.readAllBytes(logFile) : new byte[0]; }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public void stop() {
        if (logTail != null) { logTail.destroy(); }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :graph-rag-builder:test --tests '*ContainerSutTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/ContainerSut.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/env/ContainerSutTest.java
git commit -m "feat(builder): ContainerSut SutHandle reading streamed docker logs"
```

---

### Task A3: `JacocoAgent` container-reachable options

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/JacocoAgent.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/coverage/JacocoAgentOptionsTest.java`

The current `javaToolOptions()` binds `address=127.0.0.1`, unreachable from the host even with a published port. Attach mode needs `address=*` (bind all interfaces in the container) and a **fixed** port (so the override can publish it), plus the agent jar at the container mount path, not the host path.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JacocoAgentOptionsTest {
    @Test
    void containerOptionsBindAllInterfacesAtMountPath() {
        String opts = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", 6300);
        assertTrue(opts.contains("-javaagent:/grb-agents/jacocoagent.jar="));
        assertTrue(opts.contains("output=tcpserver"));
        assertTrue(opts.contains("address=*"));
        assertTrue(opts.contains("port=6300"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*JacocoAgentOptionsTest'`
Expected: FAIL (`containerJavaToolOptions` not defined).

- [ ] **Step 3: Add the static helper**

Add to `JacocoAgent`:

```java
    /** 컨테이너 내부 SUT 부착용. 호스트 published 포트로 dump하려면 컨테이너 안에서 모든 IF에 bind(=*). */
    public static String containerJavaToolOptions(String agentMountPath, int port) {
        return "-javaagent:" + agentMountPath
                + "=output=tcpserver,address=*,port=" + port;
    }

    /** 추출된 jacoco agent jar 경로 (override가 volume mount 소스로 사용). */
    public Path agentJar() { return agentJar; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :graph-rag-builder:test --tests '*JacocoAgentOptionsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/coverage/JacocoAgent.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/coverage/JacocoAgentOptionsTest.java
git commit -m "feat(builder): JacocoAgent container-reachable tcpserver options"
```

---

### Task A4: `OverrideComposeGenerator` — generate the override YAML

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/OverrideComposeGeneratorTest.java`

The override merges onto the user's compose. For the **app service** it injects: SQL+bind logging via `SPRING_APPLICATION_JSON` (Hibernate `org.hibernate.SQL=DEBUG` + `org.hibernate.orm.jdbc.bind=TRACE`, plus any MyBatis mapper namespaces at `TRACE`), the agents `JAVA_TOOL_OPTIONS` (jacoco container opts + bundled otel), a volume mounting the host agents dir to `/grb-agents:ro`, and published ports for app + jacoco. **Known caveat (documented):** compose merges scalars by *replacement*, so this override replaces the app's existing `JAVA_TOOL_OPTIONS`; we therefore include the otel agent ourselves. `SPRING_APPLICATION_JSON` likewise replaces — acceptable because logging config is additive at runtime via Spring.

Input is a small record. The generator returns YAML text; it does not touch disk (the environment writes it).

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OverrideComposeGeneratorTest {
    @Test
    void injectsLoggingAgentsAndPortsOntoAppService() throws Exception {
        var spec = new OverrideComposeGenerator.Spec(
                "app", "/host/agents", 8080, 58080, 6300, 16300,
                "-javaagent:/grb-agents/jacocoagent.jar=output=tcpserver,address=*,port=6300"
                        + " -javaagent:/grb-agents/otel-javaagent.jar",
                Map.of("com.example.mapper.OrderMapper", "TRACE"),
                Map.of("OTEL_TRACES_EXPORTER", "none", "OTEL_PROPAGATORS", "tracecontext,baggage"));
        String yaml = new OverrideComposeGenerator().generate(spec);
        JsonNode root = new YAMLMapper().readTree(yaml);
        JsonNode app = root.path("services").path("app");
        assertTrue(app.path("environment").path("JAVA_TOOL_OPTIONS").asText().contains("jacocoagent.jar"));
        assertEquals("none", app.path("environment").path("OTEL_TRACES_EXPORTER").asText());
        // SPRING_APPLICATION_JSON 은 YAML 안의 '문자열'로 보존돼야(이중 인코딩) — round-trip 검증
        assertTrue(app.path("environment").path("SPRING_APPLICATION_JSON").isTextual());
        String saj = app.path("environment").path("SPRING_APPLICATION_JSON").asText();
        assertTrue(saj.contains("logging.level.org.hibernate.SQL"));
        assertTrue(saj.contains("org.hibernate.orm.jdbc.bind"));
        assertTrue(saj.contains("com.example.mapper.OrderMapper"));
        // app + jacoco ports published "host:container"
        boolean appPort = false, jacocoPort = false;
        for (JsonNode p : app.path("ports")) {
            if (p.asText().equals("58080:8080")) appPort = true;
            if (p.asText().equals("16300:6300")) jacocoPort = true;
        }
        assertTrue(appPort && jacocoPort);
        assertEquals("/host/agents:/grb-agents:ro", app.path("volumes").get(0).asText());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*OverrideComposeGeneratorTest'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement the generator**

```java
package io.graphrag.builder.env;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.graphrag.model.Json;

import java.util.Map;
import java.util.TreeMap;

/**
 * 사용자 compose 위에 머지할 override compose를 생성한다 (attach 모드).
 * app 서비스에 SQL+bind 로깅(SPRING_APPLICATION_JSON), 커버리지/otel agent(JAVA_TOOL_OPTIONS + volume),
 * app·jacoco 포트 publish를 주입한다. SUT 이미지·소스는 건드리지 않는다(무수정).
 */
public final class OverrideComposeGenerator {

    /**
     * @param appService     compose 내 SUT(app) 서비스명
     * @param hostAgentsDir  호스트의 agents 디렉터리(jacoco/otel jar) — /grb-agents:ro 로 마운트
     * @param appContainerPort  app 컨테이너 내부 포트(예: 8080)
     * @param appHostPort       호스트 publish 포트
     * @param jacocoContainerPort  jacoco tcpserver 컨테이너 포트
     * @param jacocoHostPort       jacoco 호스트 publish 포트
     * @param javaToolOptions   주입할 JAVA_TOOL_OPTIONS (jacoco container opts + otel)
     * @param mybatisNamespaces mapper namespace → "TRACE"
     */
    public record Spec(String appService, String hostAgentsDir,
                       int appContainerPort, int appHostPort,
                       int jacocoContainerPort, int jacocoHostPort,
                       String javaToolOptions, Map<String, String> mybatisNamespaces,
                       Map<String, String> extraEnv) {}

    private static final YAMLMapper YAML = new YAMLMapper();

    public String generate(Spec spec) {
        try {
            ObjectNode root = Json.mapper().createObjectNode();
            ObjectNode services = root.putObject("services");
            ObjectNode app = services.putObject(spec.appService());

            ObjectNode env = app.putObject("environment");
            env.put("JAVA_TOOL_OPTIONS", spec.javaToolOptions());
            env.put("SPRING_APPLICATION_JSON", springApplicationJson(spec.mybatisNamespaces()));
            // OTEL 전파 env (analysis 모드의 otel.env와 동등: exporter none + baggage)
            new TreeMap<>(spec.extraEnv()).forEach(env::put);

            ArrayNode volumes = app.putArray("volumes");
            volumes.add(spec.hostAgentsDir() + ":/grb-agents:ro");

            ArrayNode ports = app.putArray("ports");
            ports.add(spec.appHostPort() + ":" + spec.appContainerPort());
            ports.add(spec.jacocoHostPort() + ":" + spec.jacocoContainerPort());

            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("override compose 생성 실패", e);
        }
    }

    /** Hibernate SQL+bind DEBUG/TRACE + MyBatis namespace TRACE 를 한 JSON 문자열로(SUT 로그에 SQL 노출). */
    private static String springApplicationJson(Map<String, String> mybatisNamespaces) {
        try {
            ObjectNode node = Json.mapper().createObjectNode();
            node.put("logging.level.org.hibernate.SQL", "DEBUG");
            node.put("logging.level.org.hibernate.orm.jdbc.bind", "TRACE");
            new TreeMap<>(mybatisNamespaces).forEach(
                    (ns, level) -> node.put("logging.level." + ns, level));
            return Json.mapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :graph-rag-builder:test --tests '*OverrideComposeGeneratorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/OverrideComposeGenerator.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/env/OverrideComposeGeneratorTest.java
git commit -m "feat(builder): generate override compose injecting logging+agents+ports"
```

---

### Task A5: `ExplorationEnvironment` interface + `AnalysisEnvironment` adoption

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/ExplorationEnvironment.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (CoverageClient construction only, in this task)

This makes `AnalysisEnvironment` expose the coverage endpoint so `build()` no longer needs the `JacocoAgent` directly for the client. The JaCoCo agent stays prepared in `build()` and passed to `start()`; `AnalysisEnvironment` records the port. This is behavior-preserving.

- [ ] **Step 1: Create the interface**

```java
package io.graphrag.builder.env;

import java.sql.Connection;
import java.sql.SQLException;

/** build()가 의존하는 분석 환경 표면. AnalysisEnvironment(Testcontainers)와 AttachedComposeEnvironment(compose) 공통. */
public interface ExplorationEnvironment extends AutoCloseable {
    SutHandle sut();
    Connection openConnection() throws SQLException;
    DbConfig.Type dbType();
    HttpCaptureServer httpCapture();      // nullable (attach v1 → null)
    String kafkaBootstrapServers();       // nullable
    String coverageHost();
    int coveragePort();
    @Override void close();
}
```

- [ ] **Step 2: Adopt in `AnalysisEnvironment`**

- Change declaration to `implements ExplorationEnvironment`.
- Add fields `private String coverageHost = "localhost"; private int coveragePort;`.
- Add a setter used by `build()` after preparing JaCoCo: `public void coverageEndpoint(String host, int port) { this.coverageHost = host; this.coveragePort = port; }`.
- Add `@Override public SutHandle sut() { return sut; }` (change return type from `SutProcess` to `SutHandle`; `sut` field stays `SutProcess`). Keep a `sutProcess()` if any caller needs the concrete type — none do.
- Add `@Override public String coverageHost() { return coverageHost; }` and `@Override public int coveragePort() { return coveragePort; }`.
- `dbType()`, `openConnection()`, `httpCapture()`, `kafkaBootstrapServers()`, `close()` already match the interface (add `@Override`).

- [ ] **Step 3: Wire in `build()`**

In `BuilderCli.build()`, after `JacocoAgent jacoco = JacocoAgent.prepare(workDir);` and inside the `try (AnalysisEnvironment env = ...)` block, after `env.start(...)`, add:

```java
            env.coverageEndpoint("localhost", jacoco.tcpPort());
```

and change `CoverageClient coverageClient = new CoverageClient("localhost", jacoco.tcpPort());` →
`CoverageClient coverageClient = new CoverageClient(env.coverageHost(), env.coveragePort());`.

- [ ] **Step 4: Compile + run analysis E2E gate (unit level first)**

Run: `./gradlew :graph-rag-builder:test`
Expected: PASS (no behavior change).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/ExplorationEnvironment.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java
git commit -m "refactor(builder): ExplorationEnvironment interface; AnalysisEnvironment owns coverage endpoint"
```

---

### Task A6: `AttachedComposeEnvironment` — compose up/down, ports, log stream

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/AttachedComposeEnvironmentTest.java` (pure-logic unit tests; the live compose path is exercised by A-E2E, not a unit test — Docker is required and asserted out-of-process)

This class runs `docker compose -f <user> -f <override> up -d --wait`, derives host endpoints, starts the log-stream process, builds a `ContainerSut`, opens JDBC to the published DB port, and on `close()` runs `docker compose ... down -v`. Port/JDBC values come from the CLI (the user knows their published ports) — see Task A7 wiring; the environment does not parse compose port mappings (kept simple and explicit).

Unit-testable seams: command construction (`upCommand`, `downCommand`) and JDBC URL building. The live `start()` is integration-level (A-E2E).

- [ ] **Step 1: Write failing unit test for command construction + jdbc url**

```java
package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttachedComposeEnvironmentTest {
    private AttachedComposeEnvironment.Config cfg() {
        return new AttachedComposeEnvironment.Config(
                Path.of("/p/docker-compose.yml"), Path.of("/p/.grb/override.yml"),
                "app", "grb-attach",
                "http://localhost:58080",
                "jdbc:postgresql://localhost:55432/app", "app", "app",
                "localhost", 16300, null, "/actuator/health", 120);
    }
    @Test void upCommandUsesBothFilesAndProjectNameAndWait() {
        List<String> cmd = AttachedComposeEnvironment.upCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "up","-d","--wait"), cmd);
    }
    @Test void downCommandRemovesVolumes() {
        List<String> cmd = AttachedComposeEnvironment.downCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "down","-v"), cmd);
    }
    @Test void logsCommandFollowsAppServiceNoPrefix() {
        List<String> cmd = AttachedComposeEnvironment.logsCommand(cfg());
        assertEquals(List.of("docker","compose","-p","grb-attach",
                "-f","/p/docker-compose.yml","-f","/p/.grb/override.yml",
                "logs","--no-log-prefix","-f","app"), cmd);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*AttachedComposeEnvironmentTest'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement the environment**

```java
package io.graphrag.builder.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * attach 모드 환경 (decision A): 사용자 compose + 생성된 override 를 빌더가 up/down 한다.
 * SQL 캡처는 app 서비스 컨테이너 로그를 파일로 흘려 ContainerSut가 byte 슬라이스로 읽는다.
 * 커버리지는 published jacoco 포트로 CoverageClient가 회수한다.
 */
public final class AttachedComposeEnvironment implements ExplorationEnvironment {

    private static final Logger log = LoggerFactory.getLogger(AttachedComposeEnvironment.class);

    /**
     * @param appBaseUri    호스트에서 본 app URL (예: http://localhost:58080)
     * @param jdbcUrl       호스트에서 본 DB JDBC URL (published DB 포트)
     * @param coverageHost  jacoco dump host (보통 localhost)
     * @param coveragePort  jacoco published 포트
     * @param kafkaBootstrap nullable — --kafka-bootstrap 미지정 시 null
     */
    public record Config(Path userCompose, Path overrideCompose, String appService, String projectName,
                         String appBaseUri, String jdbcUrl, String dbUser, String dbPass,
                         String coverageHost, int coveragePort, String kafkaBootstrap,
                         String healthPath, int readyTimeoutSeconds) {}

    private final Config config;
    private final DbConfig.Type dbType;
    private ContainerSut sut;
    private Process logTail;

    public AttachedComposeEnvironment(Config config, DbConfig.Type dbType) {
        this.config = config;
        this.dbType = dbType;
    }

    static List<String> baseCompose(Config c) {
        return new ArrayList<>(List.of("docker", "compose", "-p", c.projectName(),
                "-f", c.userCompose().toString(), "-f", c.overrideCompose().toString()));
    }
    static List<String> upCommand(Config c) {
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("up", "-d", "--wait")); return cmd;
    }
    static List<String> downCommand(Config c) {
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("down", "-v")); return cmd;
    }
    static List<String> logsCommand(Config c) {
        List<String> cmd = baseCompose(c); cmd.addAll(List.of("logs", "--no-log-prefix", "-f", c.appService())); return cmd;
    }

    /** compose up → app readiness 폴링(healthcheck 유무와 무관) → 로그 스트림 시작 → ContainerSut 구성. */
    public void start(Path workDir) {
        run(upCommand(config), "compose up");
        awaitAppReady();   // --wait는 healthcheck 없는 서비스를 기다리지 않으므로 직접 폴링(리뷰 CRITICAL)
        try {
            Path logFile = Files.createDirectories(workDir).resolve("attach-sut.log");
            Files.writeString(logFile, "");
            logTail = new ProcessBuilder(logsCommand(config))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            sut = new ContainerSut(config.appBaseUri(), logFile, logTail);
            log.info("attached to compose project {} (app {})", config.projectName(), config.appBaseUri());
        } catch (IOException e) {
            throw new UncheckedIOException("attach 로그 스트림 시작 실패", e);
        }
    }

    /** SutProcess.awaitHealthy와 동등: <appBaseUri><healthPath> 가 2xx + "UP" 될 때까지 폴링. */
    private void awaitAppReady() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.URI health = java.net.URI.create(config.appBaseUri() + config.healthPath());
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(config.readyTimeoutSeconds());
        while (java.time.Instant.now().isBefore(deadline)) {
            try {
                var resp = client.send(java.net.http.HttpRequest.newBuilder(health).GET()
                                .timeout(java.time.Duration.ofSeconds(2)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("UP")) { return; }
            } catch (Exception ignored) { /* 아직 부팅 중 */ }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        throw new IllegalStateException("attach SUT가 " + config.readyTimeoutSeconds()
                + "s 내 ready 되지 않음: " + health);
    }

    private void run(List<String> cmd, String label) {
        try {
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException(label + " 실패 (exit " + code + "): " + String.join(" ", cmd));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(label + " 실행 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " 중단됨", e);
        }
    }

    @Override public SutHandle sut() { return sut; }
    @Override public DbConfig.Type dbType() { return dbType; }
    @Override public HttpCaptureServer httpCapture() { return null; }   // attach v1: 외부 HTTP 캡처 미지원
    @Override public String kafkaBootstrapServers() { return config.kafkaBootstrap(); }
    @Override public String coverageHost() { return config.coverageHost(); }
    @Override public int coveragePort() { return config.coveragePort(); }

    @Override public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.dbUser(), config.dbPass());
    }

    @Override public void close() {
        if (logTail != null) { logTail.destroy(); }
        run(downCommand(config), "compose down");
    }
}
```

- [ ] **Step 4: Run unit test to verify it passes**

Run: `./gradlew :graph-rag-builder:test --tests '*AttachedComposeEnvironmentTest'`
Expected: PASS (command-construction + the two `down`/`logs` cases).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/env/AttachedComposeEnvironmentTest.java
git commit -m "feat(builder): AttachedComposeEnvironment (compose up/down + log stream)"
```

---

### Task A7: Extract `explore(...)` + CLI attach-mode wiring

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

Extract the body currently inside `try (AnalysisEnvironment env = ...) { env.start(...); ... }` (the post-`env.start` block, current lines ~182–319) into:

```java
private static void explore(ExplorationEnvironment env, BuildConfig config,
                            IndexResult index,
                            io.graphrag.builder.index.WsIndexResult wsIndex,
                            io.graphrag.builder.index.KafkaIndexResult kafkaIndex,
                            List<MapperStatement> mappers,
                            List<Set<String>> responseDtoFieldSets,
                            IncrementalPlan plan,
                            Map<String, List<String>> enumConstants,
                            ExplorationAccumulators acc) throws Exception
```

where `ExplorationAccumulators` is a small holder record for the mutable lists/sets the loop fills (`paths, sql, httpCalls, wsExchanges, kafkaExchanges, allSeeds, reportEntries, coveredAppBranches`, plus `runWideExec`, and out-params `totalAppBranches`). To avoid out-params, make `explore` return a small record `ExplorationResult(int totalAppBranches)` and have it mutate the passed-in collections (they are already `ArrayList`/`LinkedHashSet` locals in `build()`).

The moved block is **verbatim** except: replace `env.sut()` usages (already `SutHandle`), and construct `CoverageClient` from `env.coverageHost()/env.coveragePort()`. The `BranchCoverageAnalyzer`, runners, Kafka/WS loops are unchanged.

`build()` then becomes mode selection:

```java
        Path workDir = Files.createDirectories(config.out().resolve("work"));
        JacocoAgent jacoco = JacocoAgent.prepare(workDir);
        OtelAgent otel = OtelAgent.prepare(workDir);

        // ... accumulators declared here (paths, sql, ... runWideExec) ...

        int totalAppBranches;
        if (config.attach() != null) {
            totalAppBranches = runAttached(config, jacoco, otel, workDir, mybatisLogLevels,
                    index, wsIndex, kafkaIndex, mappers, responseDtoFieldSets, plan, enumConstants, acc);
        } else {
            SutOptions sutOptions = new SutOptions(
                    jacoco.javaToolOptions() + " " + otel.javaToolOptions(),
                    mybatisLogLevels, otel.env(config.sutId()), config.sutJavaHome());
            try (AnalysisEnvironment env =
                    new AnalysisEnvironment(config.dbConfig(), config.withRedis(), config.withKafka())) {
                env.start(config.sutJar(), workDir, sutOptions, config.externalStubsDir(), config.sutEnv());
                env.coverageEndpoint("localhost", jacoco.tcpPort());
                totalAppBranches = explore(env, config, index, wsIndex, kafkaIndex, mappers,
                        responseDtoFieldSets, plan, enumConstants, acc).totalAppBranches();
            }
        }
```

`runAttached(...)` builds the override + environment:

```java
    private static int runAttached(BuildConfig config, JacocoAgent jacoco, OtelAgent otel, Path workDir,
            Map<String, String> mybatisLogLevels, IndexResult index,
            io.graphrag.builder.index.WsIndexResult wsIndex,
            io.graphrag.builder.index.KafkaIndexResult kafkaIndex, List<MapperStatement> mappers,
            List<Set<String>> responseDtoFieldSets, IncrementalPlan plan,
            Map<String, List<String>> enumConstants, ExplorationAccumulators acc) throws Exception {
        AttachConfig at = config.attach();
        Path agentsDir = Files.createDirectories(workDir.resolve("agents"));
        // jacoco/otel jar 를 컨테이너로 mount 할 호스트 디렉터리로 모은다
        Files.copy(jacoco.agentJar(), agentsDir.resolve("jacocoagent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(otel.agentJar(), agentsDir.resolve("otel-javaagent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        int jacocoContainerPort = 6300;
        String jto = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", jacocoContainerPort)
                + " -javaagent:/grb-agents/otel-javaagent.jar";
        String overrideYaml = new OverrideComposeGenerator().generate(
                new OverrideComposeGenerator.Spec(at.appService(), agentsDir.toAbsolutePath().toString(),
                        at.appContainerPort(), at.appHostPort(), jacocoContainerPort, at.jacocoHostPort(),
                        jto, mybatisLogLevels, otel.env(config.sutId())));   // OTEL env 동등 주입
        Path overridePath = workDir.resolve("attach-override.yml");
        Files.writeString(overridePath, overrideYaml);

        var envCfg = new AttachedComposeEnvironment.Config(at.userCompose(), overridePath,
                at.appService(), "grb-attach-" + config.sutId(),
                "http://localhost:" + at.appHostPort(),
                at.jdbcUrl(), config.dbConfig().user(), config.dbConfig().password(),
                "localhost", at.jacocoHostPort(), at.kafkaBootstrap(),
                at.healthPath(), at.readyTimeoutSeconds());
        try (AttachedComposeEnvironment env = new AttachedComposeEnvironment(envCfg, config.dbConfig().type())) {
            env.start(workDir);
            return explore(env, config, index, wsIndex, kafkaIndex, mappers,
                    responseDtoFieldSets, plan, enumConstants, acc).totalAppBranches();
        }
    }
```

Define the `AttachConfig` record (new, in `BuilderCli.java` alongside `BuildConfig`):

```java
    public record AttachConfig(Path userCompose, String appService,
                               int appContainerPort, int appHostPort, int jacocoHostPort,
                               String jdbcUrl, String kafkaBootstrap,
                               String healthPath, int readyTimeoutSeconds) {}
```

New CLI options in `main()` (after compose detection), producing a nullable `AttachConfig`:

```java
        AttachConfig attach = options.containsKey("--attach")
                ? new AttachConfig(
                        Path.of(sutComposeStr),
                        required(options, "--app-service"),
                        Integer.parseInt(options.getOrDefault("--app-container-port", "8080")),
                        Integer.parseInt(required(options, "--app-port")),
                        Integer.parseInt(required(options, "--jacoco-port")),
                        required(options, "--jdbc-url"),
                        options.get("--kafka-bootstrap"),
                        options.getOrDefault("--health-path", "/actuator/health"),
                        Integer.parseInt(options.getOrDefault("--ready-timeout", "120")))
                : null;
```

**`--sut-jar` stays required in attach mode** (do NOT make it optional): `BranchCoverageAnalyzer(sutJar)`, `InputOracle.SutCode(sutSrc, sutJar)`, and — critically — `analyzer.appClassNames()` (used by `CoverageFingerprint`) all need the jar; without it every request fingerprints identically and exploration collapses (review CRITICAL). So `Path.of(required(options, "--sut-jar"))` is unchanged. `--sut-java-home` remains unused in attach (the JVM runs in the container).

**`BuildConfig`** gains two fields, `AttachConfig attach` and (Part B) `RequestHeaders requestHeaders`. To avoid breaking existing `new BuildConfig(...)` call-sites (e.g. `BuilderE2eTest`), add a **convenience overload** that omits the two new params and defaults them to `null` / `RequestHeaders.empty()`, delegating to the full canonical constructor. New call-sites in `main()` use the full constructor.

**`tables` placement:** in the extracted `explore(...)`, keep `tables = new SchemaExtractor().extract(connection)` as the first statement after `env.openConnection()`; declare `List<TableSchema> tables` as a local inside `explore()` (remove the `BuilderCli` field). `explore()` is the only user of `tables`.

**Kafka skip log:** in the moved Kafka block, when `kafkaBootstrap == null && !kafkaIndex.consumers().isEmpty()`, add `log.warn("attach mode: {} kafka consumer(s) skipped (no --kafka-bootstrap)", kafkaIndex.consumers().size());` so the limitation is visible (review).

- [ ] **Step 1: Write a failing CLI parse/guard test**

```java
package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachCliConfigTest {
    @Test void attachConfigParsedFromArgs() throws Exception {
        var opts = BuilderCli.parseArgs(new String[]{"build",
                "--sut-src","/s","--out","/o","--sut-compose","/c.yml",
                "--attach","--app-service","app","--app-port","58080",
                "--jacoco-port","16300","--jdbc-url","jdbc:postgresql://localhost:55432/app"});
        assertTrue(opts.containsKey("--attach"));
        assertEquals("app", opts.get("--app-service"));
        assertEquals("58080", opts.get("--app-port"));
    }
}
```

(If `parseArgs` is currently private, widen to package-private `static Map<String,String> parseArgs(...)` for testability — it is already `static`.)

- [ ] **Step 2: Run test to verify it fails/compiles**

Run: `./gradlew :graph-rag-builder:test --tests '*AttachCliConfigTest'`
Expected: FAIL until `parseArgs` is visible/`--attach` handled (flag with no value → parseArgs already stores `""`).

- [ ] **Step 3: Implement the extraction + wiring**

Perform the `explore(...)`/`runAttached(...)` extraction and add `AttachConfig` to `BuildConfig` and the option parsing above. Keep the moved exploration block verbatim aside from the two noted substitutions.

- [ ] **Step 4: Verify analysis mode unchanged (unit + compile)**

Run: `./gradlew :graph-rag-builder:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/cli/AttachCliConfigTest.java
git commit -m "feat(builder): attach mode wiring — explore() extraction + --attach CLI"
```

---

### Task A8: Attach-mode E2E (outer loop)

**Files:**
- Create: `e2e/run-attach-e2e.sh`

Mirrors `e2e/run-e2e.sh` style (the closest builder-flow sibling). Uses the existing `e2e/docker-compose.yml` (app service `app`, postgres published `56432:5432`, app `58080:8080`); builds the app image first; runs the builder in attach mode with `--sut-jar` + `--sut-id order`; asserts the graph; tears down and verifies no containers remain.

- [ ] **Step 1: Write the E2E script (expected RED until A1–A7 land)**

```bash
#!/usr/bin/env bash
# A-E2E: attach 모드 — 사용자 docker-compose(e2e/docker-compose.yml) + 생성 override 로 SUT를 띄우고
# 빌더가 attach 분석. graph.json 에 핵심 엔드포인트/SQL, exploration-report 에 커버 분기 > 0 검증.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/e2e/.attach-out"; PROJECT="grb-attach-order"   # = "grb-attach-" + sutId(order)
cleanup() { docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/4] order-service jar(인덱싱/분기/지문에 필수) + app 이미지 빌드 ==="
"$ROOT/gradlew" -q :samples:order-service:bootJar
docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" build app

echo "=== [2/4] 빌더 attach 실행 ==="
"$ROOT/gradlew" -q :graph-rag-builder:run --args="build \
  --sut-src $ROOT/samples/order-service/src/main/java \
  --sut-resources $ROOT/samples/order-service/src/main/resources \
  --sut-jar $ROOT/samples/order-service/build/libs/order-service.jar \
  --sut-compose $ROOT/e2e/docker-compose.yml \
  --out $OUT --sut-id order \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres"

echo "=== [3/4] 그래프 검증 ==="
python3 - "$OUT" <<'PY'
import json,sys,os
out=sys.argv[1]
g=json.load(open(os.path.join(out,"graph.json")))
assert any(e["httpMethod"]=="POST" for e in g["endpoints"]), "no POST endpoint"
assert len(g["sql"])>0, "no SQL captured (bind-value channel broken)"
r=json.load(open(os.path.join(out,"exploration-report.json")))
assert r["coveredAppBranches"]>0, "no branches covered (jacoco attach broken)"
print(f"OK endpoints={len(g['endpoints'])} sql={len(g['sql'])} coveredBranches={r['coveredAppBranches']}")
PY

echo "=== [4/4] teardown 후 잔여 컨테이너 0 검증 ==="
cleanup
remaining="$(docker compose -p "$PROJECT" -f "$ROOT/e2e/docker-compose.yml" \
  -f "$OUT/work/attach-override.yml" ps -q | wc -l | tr -d ' ')"
[ "$remaining" = "0" ] || { echo "❌ 잔여 컨테이너 $remaining"; exit 1; }
echo "✅ ATTACH-E2E PASS"
```

(Note: the builder verifies the jar path resolves; adjust `order-service.jar` to the actual `bootJar` artifact name if the module sets a version.)

- [ ] **Step 2: `chmod +x` and run after A1–A7**

Run: `chmod +x e2e/run-attach-e2e.sh && ./e2e/run-attach-e2e.sh`
Expected (after implementation): `✅ ATTACH-E2E PASS`.

- [ ] **Step 3: Commit**

```bash
git add e2e/run-attach-e2e.sh
git commit -m "test(e2e): attach-mode acceptance test"
```

---

# PART B — Custom Auth Headers (incl. per-request `{{now:...}}`)

### Task B1: `HeaderTemplate` — resolve `{{now:<pattern>}}` (Asia/Seoul) — in **shared-model**

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/HeaderTemplate.java`
- Test: `shared-model/src/test/java/io/graphrag/model/HeaderTemplateTest.java`

Placed in `shared-model` so **both** the builder (`RequestHeaders`) and testlib (`RestAssuredHelper`) reuse one resolver — they already depend on `shared-model`.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class HeaderTemplateTest {
    @Test void expandsNowInSeoulZoneAndPreservesLiterals() {
        Instant fixed = ZonedDateTime.of(2026,6,17,14,30,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        String out = HeaderTemplate.resolve("{{now:yyyyMMddHHmmss}}0900", fixed);
        assertEquals("202606171430050900", out);
    }
    @Test void plainValueUnchanged() {
        assertEquals("Bearer x", HeaderTemplate.resolve("Bearer x", Instant.now()));
    }
    @Test void multiplePlaceholders() {
        Instant fixed = ZonedDateTime.of(2026,1,2,3,4,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        assertEquals("20260102-030405", HeaderTemplate.resolve("{{now:yyyyMMdd}}-{{now:HHmmss}}", fixed));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*HeaderTemplateTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.graphrag.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 헤더 값 템플릿: {{now:<java.time 패턴>}} 를 요청 시각(Asia/Seoul)으로 치환, 나머지 리터럴 보존. */
public final class HeaderTemplate {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern NOW = Pattern.compile("\\{\\{now:([^}]+)}}");

    private HeaderTemplate() {}

    public static String resolve(String template, Instant now) {
        Matcher m = NOW.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String formatted = DateTimeFormatter.ofPattern(m.group(1)).withZone(SEOUL).format(now);
            m.appendReplacement(out, Matcher.quoteReplacement(formatted));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** fail-fast: 모든 {{now:pattern}} 의 pattern 이 유효한 java.time 패턴인지 검증(시작 시 1회). */
    public static void validate(String template) {
        Matcher m = NOW.matcher(template);
        while (m.find()) {
            DateTimeFormatter.ofPattern(m.group(1));   // 잘못된 패턴이면 IllegalArgumentException
        }
    }
}
```

(Add a `HeaderTemplateTest` case: `assertThrows(IllegalArgumentException.class, () -> HeaderTemplate.validate("{{now:zzz-bogus}}"))`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared-model:test --tests '*HeaderTemplateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared-model/src/main/java/io/graphrag/model/HeaderTemplate.java \
  shared-model/src/test/java/io/graphrag/model/HeaderTemplateTest.java
git commit -m "feat(shared-model): HeaderTemplate {{now:...}} resolver (Asia/Seoul)"
```

---

### Task B2: `RequestHeaders` — parsed header set + onLogin + per-request resolution — in **shared-model**

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/RequestHeaders.java`
- Test: `shared-model/src/test/java/io/graphrag/model/RequestHeadersTest.java`

Placed in `shared-model` so the builder (`--request-headers-file`) and testlib (`REQUEST_HEADERS` env) share one parser+resolver. Parsing format: one `Name: valueTemplate` per line; blank lines and `#` comments ignored; first `:` splits name/value; value trimmed.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest {
    @Test void parsesLinesIgnoringCommentsAndBlanks() {
        RequestHeaders h = RequestHeaders.parse(List.of(
                "# auth headers", "", "X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900",
                "X-Api-Key: abc:123"), true);
        assertTrue(h.onLogin());
        assertEquals(2, h.entries().size());
        assertEquals("abc:123", h.entries().get("X-Api-Key"));   // only first ':' splits
    }
    @Test void resolvedExpandsNowPerCall() {
        RequestHeaders h = RequestHeaders.parse(List.of("X-T: {{now:yyyyMMddHHmmss}}0900"), false);
        Instant fixed = ZonedDateTime.of(2026,6,17,14,30,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        Map<String,String> r = h.resolved(fixed);
        assertEquals("202606171430050900", r.get("X-T"));
    }
    @Test void emptyWhenNull() {
        assertTrue(RequestHeaders.empty().entries().isEmpty());
        assertFalse(RequestHeaders.empty().onLogin());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :graph-rag-builder:test --tests '*RequestHeadersTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package io.graphrag.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 탐색/로그인/생성-테스트 요청에 주입할 커스텀 헤더(값은 HeaderTemplate). onLogin=로그인 호출에도 적용 여부(입력). */
public final class RequestHeaders {

    private final Map<String, String> entries;   // name → value template
    private final boolean onLogin;

    private RequestHeaders(Map<String, String> entries, boolean onLogin) {
        this.entries = entries;
        this.onLogin = onLogin;
    }

    public static RequestHeaders empty() { return new RequestHeaders(Map.of(), false); }

    public static RequestHeaders parse(List<String> lines, boolean onLogin) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) { continue; }
            int colon = t.indexOf(':');
            if (colon < 0) { throw new IllegalArgumentException("헤더 형식 오류(‘Name: value’): " + line); }
            String value = t.substring(colon + 1).strip();
            HeaderTemplate.validate(value);   // fail-fast: 잘못된 {{now:pattern}} 은 시작 시 거부
            map.put(t.substring(0, colon).strip(), value);
        }
        return new RequestHeaders(map, onLogin);
    }

    public Map<String, String> entries() { return entries; }
    public boolean onLogin() { return onLogin; }
    public boolean isEmpty() { return entries.isEmpty(); }

    /** 이 요청 시각으로 모든 값 템플릿을 전개. */
    public Map<String, String> resolved(Instant now) {
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, HeaderTemplate.resolve(v, now)));
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared-model:test --tests '*RequestHeadersTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared-model/src/main/java/io/graphrag/model/RequestHeaders.java \
  shared-model/src/test/java/io/graphrag/model/RequestHeadersTest.java
git commit -m "feat(shared-model): RequestHeaders parse + per-request resolution"
```

---

### Task B3: Apply headers in `doSend` and (optional) login

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/AuthTokenProvider.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`

- [ ] **Step 1: Thread `RequestHeaders` into the runner**

Add `import io.graphrag.model.RequestHeaders;` and a `private final RequestHeaders extraHeaders;` field to `EndpointExplorationRunner`, a ctor param at the end, and assignment. In `doSend`, after the auth header block (around line 659-661), add:

```java
        for (Map.Entry<String, String> h : extraHeaders.resolved(Instant.now()).entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
```

(Import `java.time.Instant` and `java.util.Map` already present.) `Instant.now()` per `doSend` call = per-request freshness.

- [ ] **Step 2: Thread into `AuthTokenProvider`**

Add `import io.graphrag.model.RequestHeaders;` and `import java.time.Instant;`. Change ctor to `AuthTokenProvider(String baseUri, AuthConfig config, RequestHeaders extraHeaders)`, store the field. In `login()`, after `.header("Content-Type", "application/json")` and before `.POST(...)`, add:

```java
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUri + config.loginPath()))
                    .header("Content-Type", "application/json");
            if (extraHeaders.onLogin()) {
                Instant now = Instant.now();
                extraHeaders.resolved(now).forEach(req::header);
            }
            HttpResponse<String> response = http.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
```

(Import `java.time.Instant`.)

- [ ] **Step 3: Wire from `BuilderCli`**

In `main()`:

```java
        RequestHeaders requestHeaders = options.containsKey("--request-headers-file")
                ? RequestHeaders.parse(
                        Files.readAllLines(Path.of(options.get("--request-headers-file"))),
                        options.containsKey("--request-headers-on-login"))
                : RequestHeaders.empty();
```

Add `requestHeaders` to `BuildConfig` (canonical constructor) **and** keep the convenience overload (Task A7) defaulting it to `RequestHeaders.empty()`. In `explore(...)`/`build()`:
- `AuthTokenProvider authProvider = config.authConfig() == null ? null : new AuthTokenProvider(env.sut().baseUri(), config.authConfig(), config.requestHeaders());`
- Pass `config.requestHeaders()` as the new final arg to every `new EndpointExplorationRunner(...)`.

**Call-site sweep:** run `rg "new BuildConfig\(|new AuthTokenProvider\("` and update each — at minimum `BuilderE2eTest` (full `new BuildConfig(...)`) and `AuthTokenProviderTest` (pass `RequestHeaders.empty()`). Note (no code): the `Instant.now()` inside `AuthTokenProvider.login()` is evaluated when `login()` runs (first/expired token), which is the correct freshness for the login call — do **not** move it to `token()`.

- [ ] **Step 4: Run affected unit tests**

Run: `./gradlew :graph-rag-builder:test --tests '*AuthTokenProviderTest' --tests '*EndpointExplorationRunner*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/run/AuthTokenProvider.java \
  graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java \
  graph-rag-builder/src/test/java/io/graphrag/builder/run/AuthTokenProviderTest.java
git commit -m "feat(builder): inject custom request headers on explore + optional login"
```

---

### Task B4: B-E2E — HTTP-boundary acceptance test

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/HeaderTemplateHttpIT.java`

A `com.sun.net.httpserver.HttpServer` validates the inbound `X-AuthorizationTime` (`^\d{14}0900$` + freshness vs server now), returning 200/401. The test sends a real `HttpClient` request whose header is built exactly as `doSend` builds it (resolve `{{now:yyyyMMddHHmmss}}0900` with `Instant.now()`), asserting 200; and a header-less request asserting 401.

- [ ] **Step 1: Write the IT**

```java
package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;

class HeaderTemplateHttpIT {
    static HttpServer server; static int port;

    @BeforeAll static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/check", ex -> {
            String t = ex.getRequestHeaders().getFirst("X-AuthorizationTime");
            int code = fresh(t) ? 200 : 401;
            ex.sendResponseHeaders(code, -1); ex.close();
        });
        server.start();
    }
    @AfterAll static void down() { server.stop(0); }

    static boolean fresh(String t) {
        if (t == null || !t.matches("\\d{14}0900")) return false;
        var f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Seoul"));
        var sent = LocalDateTime.parse(t.substring(0,14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return Math.abs(java.time.Duration.between(sent, Instant.now()).toMinutes()) < 5;
    }

    @Test void freshTimestampHeaderAccepted() throws Exception {
        RequestHeaders h = RequestHeaders.parse(
                java.util.List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), false);
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/check"));
        h.resolved(Instant.now()).forEach(b::header);
        var resp = HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, resp.statusCode());
    }
    @Test void missingHeaderRejected() throws Exception {
        var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/check")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, resp.statusCode());
    }
}
```

(If compilation reports `package com.sun.net.httpserver is not visible`, add to `graph-rag-builder/build.gradle.kts`: `tasks.test { jvmArgs("--add-exports", "jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED") }` and the matching `--add-exports` to `compileTestJava`. On standard JDK 17 non-modular builds it resolves without flags.)

- [ ] **Step 2: Run it**

Run: `./gradlew :graph-rag-builder:test --tests '*HeaderTemplateHttpIT'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/run/HeaderTemplateHttpIT.java
git commit -m "test(builder): HTTP-boundary acceptance for dynamic time header"
```

---

### Task B5: testlib applies the same headers to generated-test requests (env-driven)

**Files:**
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/RestAssuredHelper.java`
- Modify: `testlib/src/main/java/io/graphrag/testlib/api/TestScope.java`
- Test: `testlib/src/test/java/io/graphrag/testlib/api/RestAssuredHelperHeadersTest.java`

Mirrors the existing env-driven `AUTH_*` pattern: the generated tests already read auth config from `Env`; custom headers do the same. `RestAssuredHelper.given()` adds the resolved custom headers to every SUT request, so **no generator/template change** is needed. `TestScope.create(Env)` parses `REQUEST_HEADERS` (newline-delimited `Name: template` lines) + `REQUEST_HEADERS_ON_LOGIN` into a `RequestHeaders` and passes it to the helper.

- [ ] **Step 1: Write the failing test**

```java
package io.graphrag.testlib.api;

import io.graphrag.model.RequestHeaders;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RestAssuredHelperHeadersTest {
    @Test void resolvedCustomHeadersExposedPerCall() {
        RequestHeaders h = RequestHeaders.parse(
                java.util.List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), false);
        RestAssuredHelper helper = new RestAssuredHelper(
                "http://localhost:1", "t-1", null, "Authorization", "Bearer", "u", "p", h);
        var resolved = helper.customHeaders(
                java.time.ZonedDateTime.of(2026,6,17,14,30,5,0, java.time.ZoneId.of("Asia/Seoul")).toInstant());
        assertEquals("202606171430050900", resolved.get("X-AuthorizationTime"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :testlib:test --tests '*RestAssuredHelperHeadersTest'`
Expected: FAIL (ctor arity / `customHeaders` missing).

- [ ] **Step 3: Implement**

In `RestAssuredHelper`, add a trailing ctor param `RequestHeaders extraHeaders` (field), a helper, and apply it in `given()`:

```java
    private final io.graphrag.model.RequestHeaders extraHeaders;

    // ... ctor gains `, RequestHeaders extraHeaders` and `this.extraHeaders = extraHeaders;`

    /** 이 요청 시각으로 전개된 커스텀 헤더(테스트 가시성용). */
    public java.util.Map<String, String> customHeaders(java.time.Instant now) {
        return extraHeaders == null ? java.util.Map.of() : extraHeaders.resolved(now);
    }

    public RequestSpecification given() {
        RequestSpecification spec = RestAssured.given()
                .baseUri(baseUri)
                .header("baggage", baggageHeaderValue());
        customHeaders(java.time.Instant.now()).forEach(spec::header);   // 매 요청 freshness
        return spec;
    }
```

In `TestScope.create(Env env)`, build the headers and pass to the helper ctor:

```java
        String headerLines = env.getOrDefault("REQUEST_HEADERS", "");
        io.graphrag.model.RequestHeaders requestHeaders = headerLines.isBlank()
                ? io.graphrag.model.RequestHeaders.empty()
                : io.graphrag.model.RequestHeaders.parse(
                        java.util.List.of(headerLines.split("\\R")),
                        env.get("REQUEST_HEADERS_ON_LOGIN") != null);
```

and append `requestHeaders` as the final arg to the existing `new RestAssuredHelper(...)` call.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :testlib:test --tests '*RestAssuredHelperHeadersTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add testlib/src/main/java/io/graphrag/testlib/api/RestAssuredHelper.java \
  testlib/src/main/java/io/graphrag/testlib/api/TestScope.java \
  testlib/src/test/java/io/graphrag/testlib/api/RestAssuredHelperHeadersTest.java
git commit -m "feat(testlib): apply env-driven custom headers to generated-test requests"
```

---

### Task B6: full-pipeline E2E — header-gated SUT, builder + generated tests both pass

**Files:**
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/auth/AuthTimeInterceptor.java`
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/auth/SecurityConfig.java` (register interceptor) — or a `WebMvcConfigurer`; pick whichever the module already uses for MVC config.
- Create: `e2e/run-auth-headers-e2e.sh`

The interceptor is **inert unless `REQUIRE_AUTH_TIME=true`** (env), so existing `./gradlew check` and `e2e/run-e2e.sh` are unaffected (regression guard). When enabled, it rejects any `/api/**` request whose `X-AuthorizationTime` is missing or not a fresh `\d{14}0900` (Asia/Seoul, ±5 min) with `401`.

- [ ] **Step 1: Implement the env-gated interceptor**

```java
package io.graphrag.sample.orders.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** REQUIRE_AUTH_TIME=true 일 때만 X-AuthorizationTime freshness 강제(기본 비활성 — 기존 e2e 불변). */
@Component
public class AuthTimeInterceptor implements HandlerInterceptor {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Value("${REQUIRE_AUTH_TIME:false}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!enabled) { return true; }
        String t = req.getHeader("X-AuthorizationTime");
        if (t == null || !t.matches("\\d{14}0900") || !fresh(t)) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "stale or missing X-AuthorizationTime");
            return false;
        }
        return true;
    }

    private boolean fresh(String t) {
        try {
            Instant sent = LocalDateTime.parse(t.substring(0, 14), FMT).atZone(SEOUL).toInstant();
            return Math.abs(Duration.between(sent, Instant.now()).toMinutes()) <= 5;
        } catch (Exception e) { return false; }
    }
}
```

Register it on `/api/**` via the module's MVC config (add a `WebMvcConfigurer` `addInterceptors` if none exists).

- [ ] **Step 2: Write the E2E script**

```bash
#!/usr/bin/env bash
# B-E2E-2: 헤더 강제 SUT 전체 파이프라인 — 빌더 탐색 + 생성 테스트 재실행 모두 X-AuthorizationTime 사용.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HDRS="$ROOT/e2e/.auth-headers.txt"
printf 'X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900\n' > "$HDRS"
export REQUIRE_AUTH_TIME=true            # SUT 인터셉터 활성 (분석 SUT 프로세스 + 생성-테스트 compose 양쪽)
export REQUEST_HEADERS="X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"   # 생성 테스트(testlib)용
"$ROOT/e2e/run-e2e.sh" --request-headers-file "$HDRS"
echo "✅ AUTH-HEADERS-E2E PASS"
```

This reuses `e2e/run-e2e.sh` (analysis mode) with the interceptor on and headers supplied to **both** the builder (`--request-headers-file`, forwarded by `run-e2e.sh` to the builder `--args`) and the generated tests (`REQUEST_HEADERS` env consumed by testlib). Add a `--request-headers-file` pass-through arg to `run-e2e.sh` (forward to the `:graph-rag-builder:run` `--args` and export `REQUEST_HEADERS` for the test run if not already set).

- [ ] **Step 3: Run after B1–B5**

Run: `chmod +x e2e/run-auth-headers-e2e.sh && ./e2e/run-auth-headers-e2e.sh`
Expected: `✅ AUTH-HEADERS-E2E PASS` (builder explores 2xx happy paths because it sends the header; generated tests pass because testlib sends it).

- [ ] **Step 4: Verify default-off regression**

Run: `./e2e/run-e2e.sh` (no `REQUIRE_AUTH_TIME`)
Expected: unchanged green — interceptor inert.

- [ ] **Step 5: Commit**

```bash
git add samples/order-service e2e/run-auth-headers-e2e.sh e2e/run-e2e.sh
git commit -m "test(e2e): full-pipeline acceptance for header-gated SUT"
```

---

# PART C — Docs

### Task C1: Attach-mode user guide + override-compose explainer

**Files:**
- Create: `docs/26-attach-mode.md`
- Modify: `README.md`, `docs/README.md`, `docs/03-graph-rag-builder.md`

- [ ] **Step 1: Write `docs/26-attach-mode.md`** covering: when to use attach mode; the decision-A lifecycle (builder owns up/down); required CLI flags (`--attach --app-service --app-port --jacoco-port --jdbc-url --sut-jar` + `[--app-container-port] [--db-service] [--kafka-bootstrap] [--health-path] [--ready-timeout]`) — note `--sut-jar`, `--sut-src`, `--sut-compose`, `--out` remain required; **what the generated override injects** (logging env, agents volume `/grb-agents`, published app+jacoco ports) and the prerequisites (SUT service name; JVM honoring `JAVA_TOOL_OPTIONS`; DB/app/jacoco ports reachable from host); **v1 limitations** (explicit, not silent): (a) **`JAVA_TOOL_OPTIONS` and `SPRING_APPLICATION_JSON` are replaced** by the override — a SUT that injects its own app config via `SPRING_APPLICATION_JSON` is unsupported in v1; move such settings to discrete env vars first; (b) `--sut-compose` must contain a recognizable DB service image (postgres/mysql/mariadb) for dialect detection, same as analysis mode; (c) no outbound-HTTP capture; (d) Kafka only with `--kafka-bootstrap`; (e) fresh-stack only, not an already-running stack. Include a worked example against `e2e/docker-compose.yml`. Also document the custom-headers flags (`--request-headers-file`, `--request-headers-on-login`), the `{{now:<pattern>}}` (Asia/Seoul) syntax with the `X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900` example, **and** that for the **generated tests** to send the same headers the test environment must set `REQUEST_HEADERS` (and optionally `REQUEST_HEADERS_ON_LOGIN`) — mirroring the existing `AUTH_*` env convention. (Use the writing-documentation skill.)

- [ ] **Step 2: Link from `README.md`** — in the "도구 1 단독" examples block, add an attach-mode invocation and a sentence linking `docs/26-attach-mode.md`; in the 문서 list add the bullet.

- [ ] **Step 3: Add to `docs/README.md` map and a cross-link in `docs/03-graph-rag-builder.md`.**

- [ ] **Step 4: Commit**

```bash
git add docs/26-attach-mode.md docs/README.md docs/03-graph-rag-builder.md README.md
git commit -m "docs: attach mode + custom auth headers user guide"
```

---

## Final verification (definition of done)

- [ ] `./gradlew check` green (all unit/integration incl. B-E2E-1 IT; `shared-model`, `testlib`, `graph-rag-builder` modules).
- [ ] `./e2e/run-e2e.sh` green (analysis mode unchanged by the refactor; interceptor inert by default).
- [ ] `./e2e/run-attach-e2e.sh` green (A-E2E).
- [ ] `./e2e/run-auth-headers-e2e.sh` green (B-E2E-2 full pipeline).
- [ ] Spec-compliance review + code-quality review (`pr-review-toolkit:code-reviewer`) triaged.
- [ ] Docs updated and committed on the same branch.

## Self-review notes

- **Spec coverage:** #2 → A1–A8; #3 → B1–B6 (incl. generated-test reproduction per the user's scope decision); guide+README → C1. Limitations explicitly logged (not silently dropped).
- **Type consistency:** `SutHandle` (6 methods) matches `SutProcess`/`ContainerSut`; `ExplorationEnvironment` accessors match both impls; `io.graphrag.model.{HeaderTemplate,RequestHeaders}` shared by builder + testlib; `RequestHeaders.resolved(Instant)` used identically in `doSend`, `login`, `RestAssuredHelper.given()`, and the ITs; `OverrideComposeGenerator.Spec` (9 fields incl. `extraEnv`) matches `runAttached`; `AttachConfig`/`AttachedComposeEnvironment.Config` carry `healthPath`/`readyTimeout`.
- **Resolved review CRITICALs:** `--sut-jar` required in attach (fingerprint integrity); app-readiness poll in `AttachedComposeEnvironment.start`; custom headers reach generated tests via testlib (B5) + full-pipeline E2E (B6).
- **Remaining risks for the code reviewer to confirm at impl time:** (1) jacoco `address=*` reachability from host via the published port (verify in A-E2E — the `coveredAppBranches>0` assertion is the guard); (2) `docker compose logs --no-log-prefix` byte-stream ordering vs `logOffset` windows under buffering (A-E2E `sql>0` is the guard); (3) v1 limitation — a SUT that injects config via its own `SPRING_APPLICATION_JSON` is unsupported (override replaces it); documented in C1, not mitigated by merge.
