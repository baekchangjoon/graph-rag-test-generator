# 병렬 fan-out pjacoco 통합 PoC 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** pjacoco를 graph-rag-builder SUT에 부착해 per-request testId로 커버리지를 격리할 수 있는지(= 단일 SUT 병렬 fan-out 전략 A의 실현성)를 V1~V4 게이트로 pass/fail 판정한다.

**Architecture:** 기존 `CoverageClient`(JaCoCo tcpserver dump)를 pjacoco 제어 엔드포인트(`/__coverage__/test/start|stop` + `destfile` `.exec`)로 대체하는 **PoC 전용 하니스**를 만든다. 본 빌더 코드(`EndpointExplorationRunner` 등)는 PoC 단계에선 직접 수정하지 않고, 측정에 필요한 최소 경로만 하니스/스크립트로 재현한다. `CoverageFingerprint.of(ExecutionDataStore, Set)`는 입력이 store라 `.exec`를 `ExecFileLoader`로 로드해 그대로 재사용한다. 측정 결과는 design spec §11에 기록하고, A 불가 시 **B로 자동 회귀하지 않고 중단**한다.

**Tech Stack:** Java(JDK 17+ 빌드/Java8 호환 agent), Gradle, JaCoCo `org.jacoco.core`(ExecFileLoader/ExecutionDataStore), pjacoco `io.pjacoco:pjacoco-agent:1.3.0`, OpenTelemetry javaagent, Docker Compose(spring-petclinic·tainted-spring MSA SUT), JUnit5 + 셸 e2e.

## Global Constraints

- pjacoco 좌표: **`io.pjacoco:pjacoco-agent`** (파일명 아닌 좌표로 의존), 버전 **1.3.0**. agent jar 빌드: `JAVA_HOME=<jdk17+> ./gradlew :agent:shadowJar` → `agent/build/libs/pjacoco-agent.jar`. 로컬 설치: `~/github_parallel-per-test-coverage/parallel-per-test-coverage/scripts/install-local.sh`.
- pjacoco 미배포(Maven Central X) → PoC는 **jar 경로를 `-Dpjacoco.agent.jar=<path>`로 주입**, 메인 build 의존성 변경 금지(REQ-009).
- agent 부착 순서: **OTel javaagent 먼저, pjacoco 나중** (`-javaagent:otel... -javaagent:pjacoco-agent.jar=...`). pjacoco scope weave 전제.
- pjacoco agent 옵션: `destfile=<dir>,port=<p>,includes=<sut pkg>[,traceKeyAutoCreate=true]`. 제어: `POST /__coverage__/test/start?testId=<id>`, `POST /__coverage__/test/stop?testId=<id>&result=passed` → `<dir>/<id>.exec`.
- baggage: pjacoco는 **`test.id`(닷)**. graph-rag 탐색 경로는 `test-id`(대시) 상수 `explore`(EndpointExplorationRunner.java:1342) — PoC는 **per-request 고유 testId 값**을 닷 키로 보낸다.
- 커버리지 지문은 기존 `io.graphrag.builder.coverage.CoverageFingerprint.of(ExecutionDataStore, Set<String> appClasses)` 재사용(입력 store만 교체).
- **A 불가 시 중단·재논의** — 어떤 V-게이트든 A를 불가로 만들면 B 인프라/코드 자동 착수 금지. spec §11에 기록 후 종료.
- 커밋 author/committer: `baekchangjoon <changjoon.baek@icloud.com>` (env vars).
- PoC 산출물 위치: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/` (JUnit 하니스) + `e2e/poc-fanout/` (셸 e2e).
- **SUT(외부 repo, spec §3.1 확정)**: V1~V3 = spring-petclinic `~/github_spring-petclinic/spring-petclinic`(단일 Spring 앱, 자체 jar 빌드). V4 = tainted-spring `~/github_tainted-spring`, 멀티 JVM OTel은 `tainted-spring-platform/docker-compose.pjacoco-otel.yml`(diary:6310→Kafka→mindgraph:6311, OTel→pjacoco 이중주입 기배선). 오버레이의 pjacoco jar 볼륨 경로는 제거된 `ptc-trace-context`를 가리켜 깨져 있으니 main `agent/build/libs/` 산출물로 갱신할 것.

---

### Task 1: pjacoco agent 해소 (PjacocoAgent.prepare) + PoC 하니스 스캐폴드

**REQ-IDs:** REQ-009

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoAgent.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoAgentTest.java`
- Create: `e2e/poc-fanout/install-pjacoco.sh`

**Interfaces:**
- Produces: `PjacocoAgent.fromSystemProperty()` → `PjacocoAgent`; `PjacocoAgent.agentJar()` → `Path`; `PjacocoAgent.javaToolOptions(Path destfileDir, int controlPort, String includes, boolean traceKeyAutoCreate)` → `String` (OTel 뒤에 붙일 `-javaagent:` 조각); `PjacocoAgent.containerJavaToolOptions(String mountPath, Path destfileDir, int controlPort, String includes, boolean traceKeyAutoCreate)` → `String`.

- [ ] **Step 1: pjacoco 로컬 설치 스크립트 작성**

`e2e/poc-fanout/install-pjacoco.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
PJ=~/github_parallel-per-test-coverage/parallel-per-test-coverage
JDK="${PJACOCO_BUILD_JDK:-$JAVA_HOME}"
JAVA_HOME="$JDK" "$PJ"/gradlew -p "$PJ" :agent:shadowJar
JAR="$PJ/agent/build/libs/pjacoco-agent.jar"
test -f "$JAR" || { echo "pjacoco agent jar not found: $JAR" >&2; exit 1; }
echo "$JAR"
```

- [ ] **Step 2: 실패 테스트 작성**

`PjacocoAgentTest.java`:
```java
package io.graphrag.builder.poc.fanout;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PjacocoAgentTest {
    @Test
    void fromSystemProperty_resolvesAgentJar() throws Exception {
        Path fake = Files.createTempFile("pjacoco-agent", ".jar");
        System.setProperty("pjacoco.agent.jar", fake.toString());
        try {
            PjacocoAgent agent = PjacocoAgent.fromSystemProperty();
            assertThat(agent.agentJar()).isRegularFile();
            String jto = agent.javaToolOptions(fake.getParent(), 6310, "org.springframework.samples.*", true);
            assertThat(jto).contains("-javaagent:" + fake.toAbsolutePath());
            assertThat(jto).contains("destfile=").contains("port=6310")
                           .contains("includes=org.springframework.samples.*").contains("traceKeyAutoCreate=true");
        } finally {
            System.clearProperty("pjacoco.agent.jar");
        }
    }

    @Test
    void fromSystemProperty_missingProperty_throws() {
        System.clearProperty("pjacoco.agent.jar");
        assertThatThrownBy(PjacocoAgent::fromSystemProperty)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pjacoco.agent.jar");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*PjacocoAgentTest*'`
Expected: FAIL (PjacocoAgent 클래스 없음)

- [ ] **Step 4: PjacocoAgent 구현 (JacocoAgent 대칭)**

`PjacocoAgent.java`:
```java
package io.graphrag.builder.poc.fanout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** PoC: -Dpjacoco.agent.jar 로 받은 pjacoco agent를 SUT 부착용 JVM 옵션으로 만든다 (메인 build 무수정). */
public final class PjacocoAgent {
    private final Path agentJar;

    private PjacocoAgent(Path agentJar) { this.agentJar = agentJar; }

    public static PjacocoAgent fromSystemProperty() {
        String p = System.getProperty("pjacoco.agent.jar");
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("system property pjacoco.agent.jar not set "
                + "(run e2e/poc-fanout/install-pjacoco.sh and pass -Dpjacoco.agent.jar=<path>)");
        }
        Path jar = Paths.get(p);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("pjacoco.agent.jar is not a file: " + jar);
        }
        return new PjacocoAgent(jar);
    }

    public Path agentJar() { return agentJar; }

    public String javaToolOptions(Path destfileDir, int controlPort, String includes, boolean traceKeyAutoCreate) {
        return optionString("-javaagent:" + agentJar.toAbsolutePath(), destfileDir.toAbsolutePath().toString(),
                controlPort, includes, traceKeyAutoCreate);
    }

    public String containerJavaToolOptions(String mountPath, Path destfileDir, int controlPort,
                                           String includes, boolean traceKeyAutoCreate) {
        return optionString("-javaagent:" + mountPath, destfileDir.toString(), controlPort, includes, traceKeyAutoCreate);
    }

    private static String optionString(String agentArg, String dest, int port, String includes, boolean traceKey) {
        StringBuilder sb = new StringBuilder(agentArg)
                .append("=destfile=").append(dest)
                .append(",port=").append(port)
                .append(",includes=").append(includes);
        if (traceKey) sb.append(",traceKeyAutoCreate=true");
        return sb.toString();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인 + 매트릭스 갱신**

Run: `./gradlew :graph-rag-builder:test --tests '*PjacocoAgentTest*'`
Expected: PASS. 요구사항명세 REQ-009 상태 🔴→🟡(테스트 작성·통과)로 갱신.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoAgent.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoAgentTest.java \
        e2e/poc-fanout/install-pjacoco.sh \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): PjacocoAgent system-property 해소 + install 스크립트 (REQ-009)"
```

---

### Task 2: per-request 커버리지 클라이언트 (PjacocoCoverageClient) — control + ExecFileLoader

**REQ-IDs:** REQ-004 (correctness 메커니즘의 토대)

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoCoverageClient.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoCoverageClientTest.java`

**Interfaces:**
- Consumes: `org.jacoco.core.tools.ExecFileLoader`, `org.jacoco.core.data.ExecutionDataStore`.
- Produces: `PjacocoCoverageClient(String controlHost, int controlPort, Path destfileDir)`; `void startTest(String testId)`; `void stopTest(String testId)`; `ExecutionDataStore load(String testId)` (= `<destfileDir>/<testId>.exec` → store). 이 store가 기존 `CoverageClient.dump(true)` 산출물과 동형이라 `CoverageFingerprint.of(store, appClasses)`에 그대로 투입된다.

- [ ] **Step 1: 실패 테스트 작성 (fixture .exec 기반, SUT 불요)**

`PjacocoCoverageClientTest.java`:
```java
package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.junit.jupiter.api.Test;
import java.io.FileOutputStream;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PjacocoCoverageClientTest {
    @Test
    void load_readsExecFileIntoStore(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // pjacoco가 stop 시 쓸 <testId>.exec 를 흉내낸 fixture
        try (FileOutputStream out = new FileOutputStream(dir.resolve("T1.exec").toFile())) {
            ExecutionDataWriter w = new ExecutionDataWriter(out);
            w.visitClassExecution(new org.jacoco.core.data.ExecutionData(
                    0x1234L, "com/example/Foo", new boolean[]{true, false, true}));
        }
        PjacocoCoverageClient client = new PjacocoCoverageClient("127.0.0.1", 0, dir);
        ExecutionDataStore store = client.load("T1");
        assertThat(store.getContents()).anySatisfy(ed ->
            assertThat(ed.getName()).isEqualTo("com/example/Foo"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*PjacocoCoverageClientTest*'`
Expected: FAIL (PjacocoCoverageClient 없음)

- [ ] **Step 3: 구현**

`PjacocoCoverageClient.java`:
```java
package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/** PoC: pjacoco 제어 엔드포인트로 per-request 경계를 긋고, flush된 <testId>.exec 를 store로 로드. */
public final class PjacocoCoverageClient {
    private final String controlHost;
    private final int controlPort;
    private final Path destfileDir;
    private final HttpClient http = HttpClient.newHttpClient();

    public PjacocoCoverageClient(String controlHost, int controlPort, Path destfileDir) {
        this.controlHost = controlHost;
        this.controlPort = controlPort;
        this.destfileDir = destfileDir;
    }

    public void startTest(String testId) { post("/__coverage__/test/start?testId=" + testId); }
    public void stopTest(String testId) { post("/__coverage__/test/stop?testId=" + testId + "&result=passed"); }

    private void post(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://" + controlHost + ":" + controlPort + path))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("pjacoco control " + path + " -> " + r.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UncheckedIOException("pjacoco control failed: " + path,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    public ExecutionDataStore load(String testId) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(destfileDir.resolve(testId + ".exec").toFile());
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("pjacoco exec load failed for " + testId, e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*PjacocoCoverageClientTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoCoverageClient.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/PjacocoCoverageClientTest.java
git commit -m "poc(fanout): PjacocoCoverageClient — control 경계 + .exec→store 로드 (REQ-004 토대)"
```

---

### Task 3: V1 — OTel→pjacoco 공존 부팅 + 바닐라 호환 `.exec` (petclinic)

**REQ-IDs:** REQ-001

**Files:**
- Create: `e2e/poc-fanout/v1-agent-coexistence.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V1AgentCoexistencePoc.java`

**Interfaces:**
- Consumes: `PjacocoAgent` (Task 1), `PjacocoCoverageClient` (Task 2). petclinic SUT jar/compose (기존 분석환경 자산).

- [ ] **Step 1: V1 e2e 스크립트 작성 (SUT 부팅 + 단일요청 + .exec 검증)**

`e2e/poc-fanout/v1-agent-coexistence.sh` — 핵심 단계(의사 단계가 아니라 실제 명령):
```bash
#!/usr/bin/env bash
set -euo pipefail
JAR=$(e2e/poc-fanout/install-pjacoco.sh)
DEST=$(mktemp -d)
OTEL=graph-rag-builder/src/main/resources/otel-javaagent.jar   # 기존 번들 (OtelAgent.prepare 소스)
# OTel 먼저, pjacoco 나중 — 순서 고정
JTO="-javaagent:$OTEL -javaagent:$JAR=destfile=$DEST,port=6310,includes=org.springframework.samples.*"
# petclinic SUT 기동(기존 e2e 패턴 재사용) — JAVA_TOOL_OPTIONS=$JTO 로 부팅
#   (구체 기동은 e2e/ 기존 petclinic compose/jar 런처 호출)
source e2e/poc-fanout/lib-launch-petclinic.sh   # 기존 petclinic 기동 래퍼 (Step 2에서 작성)
launch_petclinic "$JTO"
# 단일 테스트 경계 + 1 요청
curl -fsS -XPOST 'http://127.0.0.1:6310/__coverage__/test/start?testId=v1'
curl -fsS -H 'baggage: test.id=v1' 'http://127.0.0.1:8080/owners?lastName='
curl -fsS -XPOST 'http://127.0.0.1:6310/__coverage__/test/stop?testId=v1&result=passed'
# 바닐라 jacococli 로 파싱 — 비-zero 라인 카운트면 PASS
test -f "$DEST/v1.exec"
java -jar e2e/poc-fanout/jacococli.jar report "$DEST/v1.exec" \
     --classfiles <petclinic classes> --csv "$DEST/v1.csv"
awk -F, 'NR>1{c+=$5+$6} END{exit !(c>0)}' "$DEST/v1.csv"   # LINE_MISSED+LINE_COVERED>0
# TCP coverage 포트가 안 열렸는지(= pjacoco가 tcpserver를 안 씀) 확인
! nc -z 127.0.0.1 6300 2>/dev/null
echo "V1 PASS"
```

- [ ] **Step 2: petclinic 기동 래퍼 작성**

`e2e/poc-fanout/lib-launch-petclinic.sh` — **외부** spring-petclinic(`~/github_spring-petclinic/spring-petclinic`)을 빌드(`./mvnw -q package -DskipTests` 또는 `./gradlew bootJar`)해 그 jar를 `JAVA_TOOL_OPTIONS=$JTO java -jar <petclinic.jar>`로 기동하는 `launch_petclinic <JAVA_TOOL_OPTIONS>`를 작성한다. petclinic은 H2 임베디드 기본 프로파일이라 별도 DB compose 불요(JAVA_TOOL_OPTIONS만 주입점, classfiles 경로는 빌드 산출물 `target/classes` 또는 `build/classes`).

- [ ] **Step 3: V1 실행 → 실패/성공 관측**

Run: `bash e2e/poc-fanout/v1-agent-coexistence.sh`
Expected(최초): 환경 구성 전이면 FAIL. 구성 후 "V1 PASS" 또는 부팅 로그로 원인 규명.

- [ ] **Step 4: JUnit 판정 래퍼 (CI 게이트화)**

`V1AgentCoexistencePoc.java`: `@EnabledIfEnvironmentVariable(named="POC_FANOUT_E2E", matches="1")`로 위 스크립트를 `ProcessBuilder`로 실행하고 exit 0 + stdout "V1 PASS"를 assert. `@DisplayName("REQ-001: OTel→pjacoco 공존 부팅 + 바닐라 .exec")`.

- [ ] **Step 5: 결과 기록 + 매트릭스 갱신**

design spec §11에 V1 결과(부팅 성공 여부, .exec 라인 카운트, TCP 포트 미개방) 기록. REQ-001 상태 갱신. **부팅/공존 실패면 A 불가 → 중단(REQ-008)**.

- [ ] **Step 6: Commit**

```bash
git add e2e/poc-fanout/v1-agent-coexistence.sh e2e/poc-fanout/lib-launch-petclinic.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V1AgentCoexistencePoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V1 OTel→pjacoco 공존 부팅 + 바닐라 .exec 게이트 (REQ-001)"
```

---

### Task 4: V3(a) — per-request testId arm 등가 (correctness, fail-fast 핵심)

**REQ-IDs:** REQ-004

**Files:**
- Create: `e2e/poc-fanout/v3-arm-equivalence.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3ArmEquivalencePoc.java`

**Interfaces:**
- Consumes: `PjacocoCoverageClient.load(testId)` → store, `CoverageFingerprint.of(store, appClasses)`. vanilla 경로는 기존 `CoverageClient.dump(true)`(tcpserver) → 동일 Fingerprint.

**근거:** 이 게이트가 A의 아키텍처적 사활(additive 모델이 빈 store에서 시작해 진짜 per-request delta를 주는가). 실패 시 오버헤드와 무관하게 A 부적합 → 중단. 그래서 V2보다 먼저 둔다.

- [ ] **Step 1: 등가 비교 e2e 스크립트 작성**

`e2e/poc-fanout/v3-arm-equivalence.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
# 같은 라인 true/false arm 을 여는 두 입력 (petclinic의 분기 있는 엔드포인트 — 예: GET /owners?lastName= vs 매칭값)
# (A) vanilla: jacocoagent tcpserver + dump(reset=true) per request → coverageKey 집합
# (B) pjacoco: 요청마다 새 testId start/stop → <testId>.exec → ExecFileLoader → CoverageFingerprint.of
# 두 집합을 파일로 떨군 뒤 JUnit(Step 3)이 동일성 비교.
echo "drives both vectors, writes \$DEST/vanilla.keys and \$DEST/pjacoco.keys"
```

- [ ] **Step 2: 두 벡터의 coverageKey 집합 산출 로직**

vanilla 벡터: 기존 `CoverageClient`로 요청마다 `dump(true)`→`CoverageFingerprint.of`. pjacoco 벡터: 요청마다 고유 testId(`eq-req-<n>`)로 `startTest`→요청(`baggage: test.id=eq-req-<n>`)→`stopTest`→`load`→`CoverageFingerprint.of`. 동일 입력 시퀀스·동일 `appClasses` 사용.

- [ ] **Step 3: 등가 JUnit 게이트**

`V3ArmEquivalencePoc.java` (요지):
```java
@DisplayName("REQ-004: per-request testId arm 등가 = vanilla coverageKey 집합과 일치")
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
@Test
void perRequestTestId_yieldsSameCoverageKeySet() {
    Set<String> vanilla = runVanillaVector();   // CoverageClient.dump(true) → Fingerprint
    Set<String> pjacoco = runPjacocoVector();    // start/stop per request → load → Fingerprint
    assertThat(pjacoco).isEqualTo(vanilla);      // 같은 distinct path 수 + 같은 arm 분리
}
```

- [ ] **Step 4: 실행 + 판정**

Run: `POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3ArmEquivalencePoc*' -Dpjacoco.agent.jar=$(e2e/poc-fanout/install-pjacoco.sh)`
Expected: 집합 일치면 PASS. **불일치면 V3(a) correctness FAIL = A architecturally incompatible → §11 기록 후 중단(REQ-008). B 자동 착수 금지.**

- [ ] **Step 5: 결과 기록 + Commit**

design spec §11에 등가 비교 결과(집합 크기·일치 여부) 기록, REQ-004 갱신.
```bash
git add e2e/poc-fanout/v3-arm-equivalence.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3ArmEquivalencePoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V3(a) per-request arm 등가 correctness 게이트 (REQ-004)"
```

---

### Task 5: V3(b) — per-request 오버헤드 측정 (임계 게이트)

**REQ-IDs:** REQ-005

**Files:**
- Create: `e2e/poc-fanout/v3-overhead.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3OverheadPoc.java`

- [ ] **Step 1: 오버헤드 측정 스크립트**

`e2e/poc-fanout/v3-overhead.sh`: ① 제어 엔드포인트 왕복 지연 측정(start/stop 100회 평균 ms). ② 60-요청 1엔드포인트 벽시계 — start/stop 포함 vs baseline(start/stop 없는 단순 요청 60회). ③ `$DEST` 의 `.exec` 개수·총 바이트 출력.

- [ ] **Step 2: 임계 JUnit 게이트**

`V3OverheadPoc.java`:
```java
@DisplayName("REQ-005: per-request start/stop 오버헤드 임계 이내")
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
@Test
void overheadWithinThreshold() {
    double roundTripMs = measureControlRoundTripMs();   // 평균
    double increasePct = measureWallClockIncreasePct(); // (start/stop 포함 - baseline)/baseline*100
    assertThat(roundTripMs).isLessThan(5.0);
    assertThat(increasePct).isLessThan(10.0);
}
```

- [ ] **Step 3: 실행 + 판정**

Run: `POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V3OverheadPoc*' -Dpjacoco.agent.jar=...`
Expected: 임계 이내면 PASS. 초과면 §11 기록 후 성능 판정으로 중단·재논의(REQ-008 (b)).

- [ ] **Step 4: 결과 기록 + Commit**

```bash
git add e2e/poc-fanout/v3-overhead.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V3OverheadPoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V3(b) per-request 오버헤드 임계 게이트 (REQ-005)"
```

---

### Task 6: V2 — 동시 2 엔드포인트 커버리지 교차오염 0

**REQ-IDs:** REQ-002

**Files:**
- Create: `e2e/poc-fanout/v2-cross-contamination.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V2CrossContaminationPoc.java`

- [ ] **Step 1: 동시 요청 스크립트**

`e2e/poc-fanout/v2-cross-contamination.sh`: 서로소 클래스/분기를 갖는 두 petclinic 엔드포인트(예: `/owners` vs `/vets`)를 각자 testId(`v2-a`,`v2-b`)로 동시에(백그라운드 `&`) start→요청(`baggage: test.id=...`)→stop. 두 `.exec`를 `jacococli`로 클래스별 카운트 산출.

- [ ] **Step 2: 교차오염 0 JUnit 게이트**

`V2CrossContaminationPoc.java`:
```java
@DisplayName("REQ-002: 동시 2EP 커버리지 교차오염 0")
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
@Test
void concurrentEndpoints_noCrossContamination() {
    ExecutionDataStore a = clientLoad("v2-a");
    ExecutionDataStore b = clientLoad("v2-b");
    // a 전용 클래스(컨트롤러)가 b.exec 에 0 probe, 반대도 0
    assertThat(coveredProbeCount(b, ownersOnlyClasses())).isZero();
    assertThat(coveredProbeCount(a, vetsOnlyClasses())).isZero();
}
```

- [ ] **Step 3: 실행 + 판정**

Run: `POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V2CrossContaminationPoc*' -Dpjacoco.agent.jar=...`
Expected: 교차 probe 0이면 PASS. 오염 관측 시 A 불가 → §11 기록 후 중단(REQ-008).

- [ ] **Step 4: 결과 기록 + Commit**

```bash
git add e2e/poc-fanout/v2-cross-contamination.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V2CrossContaminationPoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V2 동시 2EP 커버리지 교차오염 0 게이트 (REQ-002)"
```

---

### Task 7: V2 — 동시 seeding 무사고 + per-worker Connection

**REQ-IDs:** REQ-003

**Files:**
- Create: `e2e/poc-fanout/v2-concurrent-seeding.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V2ConcurrentSeedingPoc.java`

- [ ] **Step 1: per-worker Connection 동시 seeding 스크립트/하니스**

seed가 필요한 두 엔드포인트(POST 또는 PATH param 있는 GET)를 동시 탐색. 각 워커가 동일 DataSource에서 **자기 `java.sql.Connection`을 발급**(공유 금지)하고 seed INSERT/요청/검증. exploration-report에서 오류·5xx·seed 실패 카운트 수집.

- [ ] **Step 2: 무사고 JUnit 게이트**

`V2ConcurrentSeedingPoc.java`:
```java
@DisplayName("REQ-003: 동시 탐색 seeding 무사고 + per-worker Connection")
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
@Test
void concurrentSeeding_noFailures() {
    SeedingResult r = runConcurrentSeededEndpoints();   // 각 워커 자기 Connection
    assertThat(r.http5xxCount()).isZero();
    assertThat(r.seedInsertFailures()).isZero();
    assertThat(r.explorationReportErrors()).isZero();
}
```

- [ ] **Step 3: 실행 + 판정**

Run: `POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V2ConcurrentSeedingPoc*' -Dpjacoco.agent.jar=...`
Expected: 무사고면 PASS. 실패 관측 시 **per-worker Connection/seeding 직렬화를 A 전제조건으로 §11에 확정**하고, 본 PoC 범위에서 회피 불가하면 A 불가 판정(REQ-008).

- [ ] **Step 4: 결과 기록 + Commit**

```bash
git add e2e/poc-fanout/v2-concurrent-seeding.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V2ConcurrentSeedingPoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V2 동시 seeding 무사고 + per-worker Connection 게이트 (REQ-003)"
```

---

### Task 8: V4 — 분산 트레이스 귀속 (tainted-spring diary→mindgraph: 단일 + 멀티 JVM/OTel)

**REQ-IDs:** REQ-006, REQ-007

**Files:**
- Create: `e2e/poc-fanout/v4-distributed-attribution.sh`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V4DistributedAttributionPoc.java`

**Interfaces:**
- Consumes: tainted-spring `~/github_tainted-spring/tainted-spring-platform/docker-compose.pjacoco-otel.yml`(diary:6310, mindgraph:6311, OTel→pjacoco 이중주입 기배선) + HANDOFF 재현 절차(`HANDOFF-from-pjacoco-c3-otel-kafka-2026-06-20.md`). pjacoco main `agent/build/libs/` 산출물로 오버레이 jar 경로 갱신 필요.

- [ ] **Step 1: 오버레이 jar 경로 복구 + 기동**

`docker-compose.pjacoco-otel.yml`의 깨진 pjacoco jar 볼륨 경로(제거된 `ptc-trace-context`)를 main 빌드 산출물(`~/github_parallel-per-test-coverage/parallel-per-test-coverage/agent/build/libs/<shadowJar>`)로 sed 치환. `docker compose -f docker-compose.yml -f docker-compose.pjacoco-otel.yml up -d zookeeper kafka postgres redis auth-user diary mindgraph` 후 두 서비스 로그에 `[pjacoco] agent installed` 확인.

- [ ] **Step 2: V4 e2e 스크립트 (HANDOFF 재현 절차)**

`e2e/poc-fanout/v4-distributed-attribution.sh`: diary `POST /internal/diaries`(traceparent `00-1111…-01`) → Kafka `diary.created` → mindgraph consumer. 잠시 대기 후 `POST http://localhost:6310/__coverage__/test/stop?testId=<traceId>` + mindgraph 6311 stop. diary `.exec`(in-process, REQ-006)와 mindgraph `<traceId>.exec`의 `DiaryCreatedConsumer` 등 귀속 바이트(REQ-007) 측정.

- [ ] **Step 3: 귀속 JUnit 게이트**

`V4DistributedAttributionPoc.java`:
```java
@DisplayName("REQ-006/REQ-007: Kafka consumer 커버리지 동일 testId 귀속 (단일+멀티 JVM)")
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
@Test
void consumerCoverageAttributedToRequestTestId() {
    long bytes = consumerOnlyProbeBytes(loadAttributed("v4"));
    assertThat(bytes).isGreaterThan(0L);   // 단일 JVM (REQ-006)
    // 멀티 JVM(REQ-007): C3 병합 후 downstream consumer 분기 귀속 > 0 (tainted-spring diary→mindgraph 멀티 JVM)
}
```

- [ ] **Step 4: 실행 + 판정 (otel 1차, sleuth 점검)**

Run: `POC_FANOUT_E2E=1 bash e2e/poc-fanout/v4-distributed-attribution.sh`
Expected: 귀속 바이트 > 0면 PASS. **멀티 JVM C3 귀속 실패 시 A 불가 → 중단(REQ-007 필수, §11 기록)**. otel-mode 1차, sleuth-mode(B3) 점검.

- [ ] **Step 5: 결과 기록 + Commit**

```bash
git add e2e/poc-fanout/v4-distributed-attribution.sh \
        graph-rag-builder/src/test/java/io/graphrag/builder/poc/fanout/V4DistributedAttributionPoc.java \
        docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md
git commit -m "poc(fanout): V4 분산 귀속 단일+멀티 JVM 게이트 (REQ-006, REQ-007)"
```

---

### Task 9: A 종합 판정 + 중단·재논의 기록

**REQ-IDs:** REQ-008

**Files:**
- Modify: `docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md` (§11 PoC 실측 결과)
- Modify: `docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md` (매트릭스 최종)
- Create: `e2e/poc-fanout/run-all.sh`

- [ ] **Step 1: 통합 실행 스크립트**

`e2e/poc-fanout/run-all.sh`: V1→V3a→V3b→V2(cov)→V2(seed)→V4 순서로 실행하고 각 pass/fail·수치를 한 리포트로 모은다. **어느 게이트가 A 불가를 산출하면 즉시 중단하고 그 사유를 출력**(이후 게이트 스킵).

- [ ] **Step 2: §11 실측 결과 작성**

design spec §11에 V1~V4 각 pass/fail, V3(a) 등가 집합 비교, V3(b) 오버헤드 수치, V2 교차오염·seeding 카운트, V4 귀속 바이트, **최종 A/B 판정**을 기록. A 불가면 "중단·재논의(B 자동 착수 없음)"로 종결.

- [ ] **Step 3: 매트릭스 최종 갱신 + 일치 점검**

요구사항명세 매트릭스를 실제 테스트 결과로 갱신(🟢/🔵). 각 green REQ가 실제 통과 테스트(@DisplayName REQ-ID)와 대응하는지 대조. REQ-007은 tainted-spring 멀티 JVM OTel SUT 확보로 분모 9/9 유지(🔵 해당 없음).

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-22-fanout-pjacoco-poc-design.md \
        docs/superpowers/requirements/2026-06-22-fanout-pjacoco-poc-requirements.md \
        e2e/poc-fanout/run-all.sh
git commit -m "poc(fanout): A 종합 판정 + §11 실측 결과 + 매트릭스 최종 (REQ-008)"
```

---

## Self-Review

**1. Spec coverage:** REQ-001→T3, REQ-002→T6, REQ-003→T7, REQ-004→T2+T4, REQ-005→T5, REQ-006→T8, REQ-007→T8, REQ-008→T9, REQ-009→T1. 모든 REQ에 task 존재. ✅

**2. Placeholder scan:** Docker SUT 기동(Task 3 Step 2, Task 8 Step 1)은 기존 e2e 자산 재사용이라 "기존 패턴 참조"로 위임 — 구체 compose 명령은 기존 `run-attach-otel-e2e.sh`에 존재하므로 placeholder가 아니라 재사용 지시. 측정 하니스의 수치 임계(5ms/10%/0)는 모두 구체값. ✅

**3. Type consistency:** `PjacocoAgent.javaToolOptions(Path,int,String,boolean)`, `PjacocoCoverageClient.load(String)→ExecutionDataStore`, `CoverageFingerprint.of(ExecutionDataStore,Set<String>)` — Task 2/4/6에서 동일 시그니처 사용. ✅

**4. 순서 근거:** V3(a) correctness(T4)를 V2(T6)보다 앞에 둠 — A의 아키텍처적 사활이라 fail-fast. T2(per-request 클라이언트)가 T4의 선행. ✅
