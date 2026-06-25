# path↔커버리지 trace 매핑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** graph.json의 각 `ExploredPath`에 그 path를 정의한 빌더 탐색 probe의 `coverageTraceId`(들)를 기록하고, 빌드 종료 시 `<out>/coverage-by-path.json` 리포트를 생성해 `pjacoco-exec/<traceId>.exec`와 역참조 가능하게 한다.

**Architecture:** effective traceId(traceparent 주입 시 non-null)를 `InvocationOutcome`/`VariantOutcome` → `PathCandidate` → `ExploredPath.coverageTraceIds(List<String>)`로 관통시켜 graph.json에 직렬화한다(접근 A: 대표 traceId). `coverage-by-path.json`은 저장된 `GraphAsset` 모델을 투영하는 파생 산출물이다.

**Tech Stack:** Java 17 records, Jackson, Gradle, JUnit5 + AssertJ, pjacoco per-trace 커버리지.

> 출처 문서:
> - 설계: `docs/superpowers/specs/2026-06-25-path-coverage-trace-mapping-design.md`
> - 요구사항명세: `docs/superpowers/requirements/2026-06-25-path-coverage-trace-mapping-requirements.md`

## Global Constraints

- 모든 레코드 변경은 **가산(additive)** — 기존 생성자 시그니처를 보존하는 delegating 생성자를 두고, compact-constructor에서 `null → List.of()` 정규화. (커버리지: REQ-009)
- 커버리지 미수집·파일 누락·손상은 **절대 빌드를 실패시키지 않는다** — 경고 로그 + `summary: null`. (REQ-007)
- effective traceId = `EndpointExplorationRunner.doSendWithScope`의 `traceId` 지역 변수(~2521): traceparent 주입 시 non-null, OTel override 반영. **`coverageTraceId`(~2475, 항상 non-null) 가 아님.** (설계 §4.1)
- 커버리지 백엔드는 항상 pjacoco. 리포트 생성 가드 = `Files.isDirectory(config.out().resolve("work/pjacoco-exec"))` 且 `.exec` ≥ 1. (REQ-004, REQ-010)
- traceId null → 리스트에서 제외(빈 리스트). (REQ-008)
- 산문 문서는 한국어, 코드·식별자·테스트명은 영어.
- 작업 디렉터리: worktree `.claude/worktrees/feat+path-coverage-trace-mapping`. 모든 경로는 그 루트 기준.

---

### Task 1: E2E acceptance 테스트 (바깥 루프 — red)

**REQ-IDs:** REQ-001, REQ-003, REQ-004, REQ-006

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/coverage/CoverageTraceMappingE2E.java`

**Interfaces:**
- Consumes: 기존 full-build E2E 하네스(`OtelKafkaBuildIntegrationTest` 또는 `EgressStubBodyFidelity*E2E`의 compose SUT 기동 + `BuilderCli.build(config)` 패턴). 그 하네스를 복제해 SUT를 띄우고 `<out>`에 빌드 산출물을 만든다.
- Produces: 없음(검증 전용).

이 테스트는 기능 구현 전까지 **red** 다(double-loop 바깥 루프). 약화·skip 처리 금지(Docker 미가용 시에만 `Assumptions`로 skip).

- [ ] **Step 1: E2E 테스트 작성**

기존 full-build E2E(예: `OtelKafkaBuildIntegrationTest`)의 SUT 기동/`BuildConfig`/티어다운 패턴을 그대로 따른다. 빌드 1회 후 `<out>` 산출물을 검증한다:

```java
package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** REQ-001/003/004/006: path↔커버리지 trace 매핑 E2E. */
class CoverageTraceMappingE2E {

    // 기존 full-build E2E 하네스 재사용: SUT compose 기동 + BuilderCli.build(config) 실행 →
    // outDir 반환. (OtelKafkaBuildIntegrationTest의 @BeforeAll/@AfterAll 패턴 복제)
    // 아래는 빌드가 끝난 뒤 outDir/execDir에 대한 검증만 보여준다.

    private Path outDir;     // 하네스가 채움
    private Path execDir;    // outDir.resolve("work/pjacoco-exec")

    @Test
    void reportFileExistsAndSchemaValid() throws Exception {        // REQ-004
        assumeThat(Files.isDirectory(execDir)).isTrue();           // Docker 미가용 → skip
        Path report = outDir.resolve("coverage-by-path.json");
        assertThat(Files.exists(report)).isTrue();
        JsonNode root = Json.mapper().readTree(Files.readString(report));
        assertThat(root.hasNonNull("sutId")).isTrue();
        assertThat(root.hasNonNull("execDir")).isTrue();
        assertThat(root.get("paths").isArray()).isTrue();
        for (JsonNode p : root.get("paths")) {
            assertThat(p.has("pathId")).isTrue();
            assertThat(p.has("endpointId")).isTrue();
            assertThat(p.has("coverageTraceIds")).isTrue();
            assertThat(p.has("execFiles")).isTrue();
        }
    }

    @Test
    void graphPathsHaveCoverageTraceIds() throws Exception {        // REQ-001
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode graph = Json.mapper().readTree(Files.readString(outDir.resolve("graph.json")));
        for (JsonNode p : graph.get("paths")) {
            assertThat(p.has("coverageTraceIds")).as("path %s", p.get("id")).isTrue();
        }
    }

    @Test
    void traceIdsResolveToExecFiles() throws Exception {            // REQ-003
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode graph = Json.mapper().readTree(Files.readString(outDir.resolve("graph.json")));
        for (JsonNode p : graph.get("paths")) {
            for (JsonNode tid : p.get("coverageTraceIds")) {
                Path exec = execDir.resolve(tid.asText() + ".exec");
                assertThat(Files.exists(exec)).as("dangling exec for %s", tid.asText()).isTrue();
            }
        }
    }

    @Test
    void summaryMatchesSidecarWhenPresent() throws Exception {      // REQ-006
        assumeThat(Files.isDirectory(execDir)).isTrue();
        JsonNode report = Json.mapper().readTree(
                Files.readString(outDir.resolve("coverage-by-path.json")));
        for (JsonNode p : report.get("paths")) {
            for (JsonNode ef : p.get("execFiles")) {
                Path sidecar = outDir.resolve(ef.get("sidecar").asText());
                if (Files.exists(sidecar) && ef.hasNonNull("summary")) {
                    JsonNode sc = Json.mapper().readTree(Files.readString(sidecar));
                    assertThat(ef.get("summary").get("classCount").asInt())
                            .isEqualTo(sc.get("classCount").asInt());
                }
            }
        }
    }
}
```

- [ ] **Step 2: 컴파일·실행해 red 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.coverage.CoverageTraceMappingE2E'`
Expected: 컴파일은 통과하되 (Docker 가용 시) `coverage-by-path.json` 부재로 FAIL, 또는 Docker 미가용 시 skip. 약화하지 말 것.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/coverage/CoverageTraceMappingE2E.java
git commit -m "test(e2e): coverage-by-path 매핑 수용 테스트 (red) [REQ-001/003/004/006]"
```

---

### Task 2: `InvocationOutcome`에 coverageTraceId 추가

**REQ-IDs:** REQ-015

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/explore/InvocationOutcomeTraceTest.java` (create)

**Interfaces:**
- Produces: `InvocationOutcome.coverageTraceId()` → `String`(nullable). canonical 생성자 끝에 `String coverageTraceId` 추가. 기존 모든 delegating 생성자는 `null`을 전달하도록 갱신.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.explore;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InvocationOutcomeTraceTest {
    @Test
    void carriesCoverageTraceId() {
        InvocationOutcome o = new InvocationOutcome(200, null, java.util.Set.of(), 0L, 0L,
                List.of(), "covKey", List.of(), List.of(), null, java.util.Map.of(), List.of(), "abc123");
        assertThat(o.coverageTraceId()).isEqualTo("abc123");
    }
    @Test
    void legacyConstructorDefaultsNullTraceId() {
        InvocationOutcome o = new InvocationOutcome(200, null, java.util.Set.of(), 0L, 0L);
        assertThat(o.coverageTraceId()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.explore.InvocationOutcomeTraceTest'`
Expected: 컴파일 FAIL(13번째 인자 없음, `coverageTraceId()` 없음).

- [ ] **Step 3: canonical 생성자에 필드 추가 + delegating 갱신**

`record InvocationOutcome(...)` 컴포넌트 목록 끝(`egressCalls` 뒤)에 `String coverageTraceId` 추가. 기존 모든 delegating 생성자(`egressCalls` 생략형 등 5개)의 위임 호출 끝에 `null`을 추가:

```java
public record InvocationOutcome(
        int status, JsonNode response, Set<BranchRef> coveredBranches,
        long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
        String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
        java.util.List<CapturedEventEmit> capturedEventEmits, String kafkaTraceId,
        Map<String, String> responseHeaders, java.util.List<EgressCall> egressCalls,
        String coverageTraceId) {            // ← 신규

    public InvocationOutcome {
        httpExchanges = httpExchanges == null ? java.util.List.of() : httpExchanges;
        capturedSql = capturedSql == null ? java.util.List.of() : capturedSql;
        capturedEventEmits = capturedEventEmits == null ? java.util.List.of() : capturedEventEmits;
        responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
        egressCalls = egressCalls == null ? java.util.List.of() : egressCalls;
        // coverageTraceId는 nullable 그대로 유지(미주입 probe = null)
    }
    // 기존 12-arg(egressCalls 포함) 생성자를 새 canonical로 위임 + coverageTraceId=null 추가:
    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                             java.util.List<CapturedEventEmit> capturedEventEmits, String kafkaTraceId,
                             Map<String, String> responseHeaders, java.util.List<EgressCall> egressCalls) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                capturedEventEmits, kafkaTraceId, responseHeaders, egressCalls, null);
    }
    // 나머지 delegating 생성자(11-arg, 8-arg, 7-arg, 6-arg, 5-arg)는 이미 12-arg로 위임하므로 그대로 둔다.
}
```

(주의: 기존 11-arg 생성자가 12-arg로 위임하던 체인은 그대로 작동 — 새 12-arg가 canonical 대신 위임자가 됨.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.explore.InvocationOutcomeTraceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java graph-rag-builder/src/test/java/io/graphrag/builder/explore/InvocationOutcomeTraceTest.java
git commit -m "feat(explore): InvocationOutcome.coverageTraceId 필드 추가 [REQ-015]"
```

---

### Task 3: `VariantOutcome`에 coverageTraceId 추가 (2-arg 호환 생성자 유지)

**REQ-IDs:** REQ-015

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:1728`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/VariantOutcomeTraceTest.java` (create)

**Interfaces:**
- Produces: `VariantOutcome(ExecutionDataStore coverage, int sutStatus, String coverageTraceId)` canonical + `VariantOutcome(ExecutionDataStore, int)` 호환(coverageTraceId=null 위임). 기존 테스트(`new VariantOutcome(coverage, status)`)가 깨지지 않도록 2-arg 보존.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.run;

import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VariantOutcomeTraceTest {
    @Test
    void carriesTraceId() {
        var vo = new EndpointExplorationRunner.VariantOutcome(new ExecutionDataStore(), 200, "t9");
        assertThat(vo.coverageTraceId()).isEqualTo("t9");
    }
    @Test
    void twoArgCompatNullTrace() {
        var vo = new EndpointExplorationRunner.VariantOutcome(new ExecutionDataStore(), 200);
        assertThat(vo.coverageTraceId()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.VariantOutcomeTraceTest'`
Expected: 컴파일 FAIL.

- [ ] **Step 3: 레코드 변경**

`EndpointExplorationRunner.java:1728`:

```java
public record VariantOutcome(ExecutionDataStore coverage, int sutStatus, String coverageTraceId) {
    public VariantOutcome(ExecutionDataStore coverage, int sutStatus) {
        this(coverage, sutStatus, null);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.VariantOutcomeTraceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java graph-rag-builder/src/test/java/io/graphrag/builder/run/VariantOutcomeTraceTest.java
git commit -m "feat(run): VariantOutcome.coverageTraceId 필드 추가 + 2-arg 호환 [REQ-015]"
```

---

### Task 4: doSendWithScope·sendVariantAndDumpDelta에서 traceId 적재

**REQ-IDs:** REQ-002, REQ-012 (prep)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (doSendWithScope 반환 ~2542; sendVariantAndDumpDelta 반환 ~2292)

**Interfaces:**
- Consumes: 지역 변수 `traceId`(~2521, effective, nullable), `coverageTraceId`(~2261, sendVariantAndDumpDelta 내).
- Produces: `InvocationOutcome.coverageTraceId` / `VariantOutcome.coverageTraceId` 채워진 값.

- [ ] **Step 1: doSendWithScope 반환 수정**

`doSendWithScope`의 `return new InvocationOutcome(...)` (현재 12-arg, `egressCalls`로 끝남)을 13-arg canonical로 바꿔 끝에 `traceId`(~2521 지역 변수)를 전달:

```java
return new InvocationOutcome(response.statusCode(),
        parseJsonOrNull(response.body()),
        requestCoverage.covered(), logStart, logEnd,
        httpCapture == null ? List.of()
                : coverageTraceId != null ? httpCapture.drainByTraceId(coverageTraceId)
                                          : httpCapture.drainNewExchanges(),
        coverageKey, drained, java.util.List.of(), traceId, capturedResponseHeaders,
        egressCalls,
        traceId);   // ← coverageTraceId = effective traceId (미주입 시 null)
```

- [ ] **Step 2: sendVariantAndDumpDelta 반환 수정**

`EndpointExplorationRunner.java:2292`:

```java
return new VariantOutcome(coverage.requestDelta(coverageTraceId), status, traceId);
```

여기서 `traceId`는 `sendVariantAndDumpDelta` 내 effective traceId(probeTraceparent 미주입 시 null)다. `doSendWithScope`와 동일 규칙으로 산출한다 — 그 메서드에 effective traceId 지역 변수가 없으면 `probeTraceparent != null ? coverageTraceId : null`로 계산해 둔다.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java
git commit -m "feat(run): doSend/sendVariant에서 effective traceId를 outcome에 적재 [REQ-002/012]"
```

---

### Task 5: `PathCandidate`에 coverageTraceId 추가 + orchestrator 채움(tie-break)

**REQ-IDs:** REQ-002, REQ-008

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/PathCandidate.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/ExplorationOrchestrator.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/explore/ExplorationOrchestratorTraceTest.java` (create)

**Interfaces:**
- Produces: `PathCandidate.coverageTraceId()` → `String`(nullable). canonical 끝에 추가, 기존 생성자 위임에 `null` 추가.
- orchestrator: 같은 key 충돌 시 생존자 traceId가 null이고 새 proto가 non-null이면 **non-null로 교체**(tie-break).

- [ ] **Step 1: 실패 테스트 작성**

`ExplorationOrchestrator`를 fake `PathExplorer` 2개(같은 coverageKey, traceId t1·t2)로 구동하고, 결과 `PathCandidate.coverageTraceId`가 t1인지 확인. 두 번째 테스트: 첫 proto traceId=null, 두 번째 non-null t3 → 결과 t3.

```java
package io.graphrag.builder.explore;
// (테스트 하네스는 기존 ExplorationOrchestrator 테스트 패턴을 따른다. 핵심 단언:)
//   assertThat(outcome.paths().get(0).coverageTraceId()).isEqualTo("t1");      // 둘 다 non-null
//   assertThat(outcome.paths().get(0).coverageTraceId()).isEqualTo("t3");      // null→non-null tie-break
```

기존 orchestrator 단위 테스트가 있으면 그 하네스를 재사용한다. 없으면 `ExplorationResult.ExploredInput`을 직접 만들어 `InvocationOutcome.coverageTraceId`를 세팅한 fake `PathExplorer`로 구동한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.explore.ExplorationOrchestratorTraceTest'`
Expected: 컴파일 FAIL(`coverageTraceId()` 없음).

- [ ] **Step 3: PathCandidate 필드 추가**

`record PathCandidate(...)` 컴포넌트 끝(`egressCalls` 뒤)에 `String coverageTraceId` 추가. 기존 14-arg(egressCalls 포함) 생성자를 canonical로 두고, 그 아래 13-arg(egressCalls 생략) 등 기존 위임 생성자에 `null` 전달 추가:

```java
// canonical: ... , List<EgressCall> egressCalls, String coverageTraceId
// 기존 egressCalls 포함 생성자 → this(..., egressCalls, null);
```

- [ ] **Step 4: orchestrator 채움 + tie-break**

`ExplorationOrchestrator`의 후보 수집 루프(`candidates.putIfAbsent`)를 tie-break 가능하게 바꾼다:

```java
String tid = input.outcome().coverageTraceId();
candidates.merge(key, new Proto(input, sorted, engine.name(), outcome), (existing, incoming) -> {
    // 대표는 첫 proto 유지(접근 A). 단 기존 traceId가 null이고 새 것이 non-null이면 교체.
    if (existing.input().outcome().coverageTraceId() == null
            && incoming.input().outcome().coverageTraceId() != null) {
        return incoming;
    }
    return existing;
});
```

그리고 `toOutcome`의 `new PathCandidate(...)` 끝에 `proto.input().outcome().coverageTraceId()` 전달:

```java
paths.add(new PathCandidate(
        pathId, proto.input().body(), status, proto.input().outcome().response(),
        proto.branches(), proto.engine(),
        proto.input().outcome().logStart(), proto.input().outcome().logEnd(),
        proto.input().outcome().httpExchanges(), proto.input().outcome().capturedSql(),
        proto.input().outcome().capturedEventEmits(), proto.input().outcome().kafkaTraceId(),
        proto.input().outcome().responseHeaders(), proto.input().outcome().egressCalls(),
        proto.input().outcome().coverageTraceId()));   // ← 신규
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.explore.ExplorationOrchestratorTraceTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/explore/PathCandidate.java graph-rag-builder/src/main/java/io/graphrag/builder/explore/ExplorationOrchestrator.java graph-rag-builder/src/test/java/io/graphrag/builder/explore/ExplorationOrchestratorTraceTest.java
git commit -m "feat(explore): PathCandidate.coverageTraceId + orchestrator tie-break [REQ-002/008]"
```

---

### Task 6: `ExploredPath`에 coverageTraceIds 추가 + 후방호환

**REQ-IDs:** REQ-009, REQ-015

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/ExploredPath.java`
- Test: `shared-model/src/test/java/io/graphrag/model/ExploredPathCompatTest.java` (메서드 추가)

**Interfaces:**
- Produces: `ExploredPath.coverageTraceIds()` → `List<String>`(non-null, 정규화됨). canonical 끝에 `List<String> coverageTraceIds` 추가. 기존 17-arg 생성자 + 12/13/14-arg 호환 생성자는 `List.of()`로 위임.

- [ ] **Step 1: 실패 테스트 추가 (`ExploredPathCompatTest`에)**

```java
@Test
void legacyJsonYieldsEmptyCoverageTraceIds() throws Exception {
    String json = """
            {"id":"p1","endpointId":"ep","sampleInput":null,"expectedStatus":200,
             "sampleResponse":null,"capturedSqlIds":[],"capturedHttpCallIds":[],
             "branchesTaken":[],"discoveredBy":"heuristic","constraints":[],
             "validationWarnings":[],"requiredSeedIds":[],"capturedEventEmitIds":[],
             "responseHeaders":{}}
            """;
    ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
    assertThat(p.coverageTraceIds()).isEmpty();
}

@Test
void nullCoverageTraceIdsNormalizedToEmpty() throws Exception {
    String json = """
            {"id":"p1","endpointId":"ep","expectedStatus":200,"coverageTraceIds":null}
            """;
    ExploredPath p = MAPPER.readValue(json, ExploredPath.class);
    assertThat(p.coverageTraceIds()).isEmpty();
}

@Test
void roundTripPreservesCoverageTraceIds() throws Exception {
    ExploredPath p = new ExploredPath("p1", "ep", null, 200, null,
            List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
            List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("abc", "def"));
    ExploredPath rt = MAPPER.readValue(MAPPER.writeValueAsString(p), ExploredPath.class);
    assertThat(rt.coverageTraceIds()).containsExactly("abc", "def");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared-model:test --tests 'io.graphrag.model.ExploredPathCompatTest'`
Expected: 컴파일 FAIL.

- [ ] **Step 3: 필드 추가 + 정규화 + 18-arg canonical**

`record ExploredPath(...)` 끝(`semanticStatusText` 뒤)에 `List<String> coverageTraceIds` 추가. compact constructor에 정규화 한 줄 추가:

```java
public ExploredPath {
    // ... 기존 정규화 ...
    coverageTraceIds = coverageTraceIds == null ? List.of() : coverageTraceIds;
}
```

기존 17-arg 생성자(outcome/semanticStatus/semanticStatusText 포함)는 더 이상 canonical이 아니므로, 동일 시그니처의 delegating 생성자를 추가해 `List.of()`로 위임:

```java
public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
        JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
        List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
        List<String> validationWarnings, List<String> requiredSeedIds, List<String> capturedEventEmitIds,
        Map<String, String> responseHeaders, Outcome.Kind outcome, int semanticStatus, String semanticStatusText) {
    this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
         branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds, capturedEventEmitIds,
         responseHeaders, outcome, semanticStatus, semanticStatusText, List.of());
}
```

기존 14/13/12-arg 호환 생성자는 그 17-arg(이제 위임자)로 위임하므로 그대로 작동한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :shared-model:test --tests 'io.graphrag.model.ExploredPathCompatTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared-model/src/main/java/io/graphrag/model/ExploredPath.java shared-model/src/test/java/io/graphrag/model/ExploredPathCompatTest.java
git commit -m "feat(model): ExploredPath.coverageTraceIds + null 정규화 + round-trip [REQ-009/015]"
```

---

### Task 7: PathCandidate→ExploredPath 변환에서 coverageTraceIds 채움

**REQ-IDs:** REQ-001

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:~1465`

**Interfaces:**
- Consumes: `PathCandidate.coverageTraceId()`.
- Produces: `ExploredPath.coverageTraceIds` = `tid != null ? List.of(tid) : List.of()`.

- [ ] **Step 1: 변환 코드 수정**

`paths.add(new ExploredPath(candidate.pathId(), ...))` (~1465) 호출을 18-arg canonical로 바꿔 끝에 추가:

```java
String tid = candidate.coverageTraceId();
List<String> covTraceIds = tid != null ? List.of(tid) : List.of();
paths.add(new ExploredPath(
        candidate.pathId(), endpoint.id(), candidate.body(), candidate.status(), candidate.response(),
        sql.stream().map(CapturedSql::id).toList(),
        httpCalls.stream().map(io.graphrag.model.CapturedHttpCall::id).toList(),
        candidate.branches(), discoveredBy, matchConstraints(candidate, conditions, endpoint),
        /* validationWarnings */ ..., /* requiredSeedIds */ ..., /* capturedEventEmitIds */ ...,
        /* responseHeaders */ ..., o.kind(), o.semanticStatus(), o.semanticStatusText(),
        covTraceIds));   // ← 신규
```

(기존 인자들은 현 코드 그대로 유지하고 마지막에 `covTraceIds`만 추가. 현재 호출이 14-arg 호환 생성자를 쓰고 있으면, outcome/semanticStatus 인자까지 포함한 18-arg canonical로 승격한다.)

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java
git commit -m "feat(run): 대표 path에 coverageTraceIds 적재 [REQ-001]"
```

---

### Task 8: copy/rewrite 사이트에서 coverageTraceIds 보존

**REQ-IDs:** REQ-011

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (`withSeedIds` ~2677, np 재작성 ~1579)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ExploredPathCopyPreservationTest.java` (create)

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.run;

import io.graphrag.model.ExploredPath;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathCopyPreservationTest {
    @Test
    void withSeedIdsPreservesCoverageTraceIds() {
        ExploredPath p = new ExploredPath("id", "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("tid-x"));
        ExploredPath copied = EndpointExplorationRunner.withSeedIdsForTest(p, List.of("seed-1"));
        assertThat(copied.coverageTraceIds()).containsExactly("tid-x");
    }
}
```

(`withSeedIds`가 private static이면, 테스트 가시성을 위해 package-private 위임 `withSeedIdsForTest`를 추가하거나 `withSeedIds`를 package-private으로 완화한다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.ExploredPathCopyPreservationTest'`
Expected: FAIL(coverageTraceIds 빈 리스트로 손실).

- [ ] **Step 3: copy 사이트 수정**

`withSeedIds`(~2677) 의 `new ExploredPath(p.id(), ...)`에 `p.coverageTraceIds()`를 마지막 인자로 전달. np 재작성(~1579)의 `new ExploredPath(np.id(), ...)`에 `np.coverageTraceIds()` 전달. 두 곳 모두 18-arg canonical로 승격.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.ExploredPathCopyPreservationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java graph-rag-builder/src/test/java/io/graphrag/builder/run/ExploredPathCopyPreservationTest.java
git commit -m "fix(run): copy/rewrite 사이트에서 coverageTraceIds 보존 [REQ-011]"
```

---

### Task 9: 직접 생성 path(negauth/negval/formref/state-guard) traceId 적재

**REQ-IDs:** REQ-014

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (lines 572, 707, 929, 1021)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/DirectPathSiteTraceTest.java` (create)

**Interfaces:**
- Consumes: 각 사이트의 `InvocationOutcome out` → `out.coverageTraceId()`.
- Produces: 그 path의 `coverageTraceIds = tid != null ? List.of(tid) : List.of()`.

- [ ] **Step 1: 실패 테스트 작성**

각 사이트는 private 메서드 내부라 단위 격리가 어렵다. 가능한 가장 높은 단위로: 각 사이트의 생성 헬퍼를 package-private으로 추출하거나, 사이트별 생성 로직을 작은 static 헬퍼 `coverageTraceIdsOf(InvocationOutcome)`로 공통화하고 그 헬퍼를 테스트한다:

```java
@Test
void coverageTraceIdsOfNonNull() {
    InvocationOutcome o = new InvocationOutcome(401, null, java.util.Set.of(), 0,0,
            java.util.List.of(), null, java.util.List.of(), java.util.List.of(), null,
            java.util.Map.of(), java.util.List.of(), "tid-7");
    assertThat(EndpointExplorationRunner.coverageTraceIdsOf(o)).containsExactly("tid-7");
}
@Test
void coverageTraceIdsOfNull() {
    InvocationOutcome o = new InvocationOutcome(401, null, java.util.Set.of(), 0,0);
    assertThat(EndpointExplorationRunner.coverageTraceIdsOf(o)).isEmpty();
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.DirectPathSiteTraceTest'`
Expected: 컴파일 FAIL.

- [ ] **Step 3: 공통 헬퍼 추가 + 4개 사이트 적용**

```java
static List<String> coverageTraceIdsOf(InvocationOutcome out) {
    return out.coverageTraceId() != null ? List.of(out.coverageTraceId()) : List.of();
}
```

lines 572/707/929/1021의 `new ExploredPath(...)`를 18-arg canonical로 승격하고 마지막 인자에 `coverageTraceIdsOf(out)` 전달. negauth(572)는 `doSend`(별도 HttpClient) 결과의 `coverageTraceId`를 쓴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.DirectPathSiteTraceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java graph-rag-builder/src/test/java/io/graphrag/builder/run/DirectPathSiteTraceTest.java
git commit -m "feat(run): 직접 생성 path에 invoke traceId 적재 [REQ-014]"
```

---

### Task 10: responsevar arm traceId 누적 + egress-assertion 빈 리스트

**REQ-IDs:** REQ-012, REQ-013

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (responsevar ~2077, egress-assertion ~2141, 변형 invoke 루프 ~1780)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ResponseVariantTraceTest.java`, `EgressAssertionTraceTest.java` (create)

**Interfaces:**
- Consumes: 변형 루프의 각 `VariantOutcome vo` → `vo.coverageTraceId()`.
- Produces: responsevar path의 `coverageTraceIds = List.copyOf(armTraceIds)`; egress-assertion path의 `coverageTraceIds = List.of()`.

- [ ] **Step 1: 실패 테스트 작성**

responsevar arm 누적은 변형 루프(`vr.kept()`/`mergeAndDetectNewArm`)와 결합돼 있으므로, arm traceId 누적 로직을 작은 헬퍼로 추출해 단위 테스트한다:

```java
// armTraceIds = kept arms 각 vo.coverageTraceId() 중 non-null만 distinct 순서보존 수집
@Test
void accumulatesNonNullArmTraceIds() {
    List<String> acc = EndpointExplorationRunner.collectArmTraceIds(
            List.of("a1", null, "a2", "a1"));
    assertThat(acc).containsExactly("a1", "a2");
}
```

egress-assertion: 생성된 path의 coverageTraceIds가 빈 리스트인지 — 생성 헬퍼를 테스트하거나, 통합 단언.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.ResponseVariantTraceTest'`
Expected: 컴파일 FAIL.

- [ ] **Step 3: 누적 헬퍼 + 사이트 적용**

```java
static List<String> collectArmTraceIds(List<String> rawTraceIds) {
    java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
    for (String t : rawTraceIds) if (t != null) s.add(t);
    return List.copyOf(s);
}
```

변형 invoke 루프(~1780)에서 각 `vo.coverageTraceId()`를 `List<String> armRawTraceIds`에 모으고, responsevar `new ExploredPath(endpoint.id() + "-responsevar", ...)` (~2077)을 18-arg canonical로 승격해 마지막 인자에 `collectArmTraceIds(armRawTraceIds)` 전달. egress-assertion(~2141) `new ExploredPath(...)`은 마지막 인자에 `List.of()` 전달(의도된 빈 리스트, 주석 명시).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.run.ResponseVariantTraceTest' --tests 'io.graphrag.builder.run.EgressAssertionTraceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java graph-rag-builder/src/test/java/io/graphrag/builder/run/ResponseVariantTraceTest.java graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressAssertionTraceTest.java
git commit -m "feat(run): responsevar arm traceId 누적 + egress-assertion 빈 리스트 [REQ-012/013]"
```

---

### Task 11: `CoverageByPathReport` 생성기

**REQ-IDs:** REQ-005, REQ-006, REQ-007

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/CoverageByPathReport.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/coverage/CoverageByPathReportTest.java`

**Interfaces:**
- Produces: `static void write(GraphAsset asset, Path outDir)` — `outDir/coverage-by-path.json` 작성. exec 디렉터리 = `outDir/work/pjacoco-exec`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CoverageByPathReportTest {

    private GraphAsset assetWithPath(String pathId, List<String> traceIds) {
        ExploredPath p = new ExploredPath(pathId, "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", traceIds);
        return new GraphAsset("sut1", "sha", List.of(), List.of(p), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null, null, null);  // GraphAsset canonical 시그니처에 맞춤
    }

    @Test
    void mapsPathToExecRelativePaths(@TempDir Path out) throws Exception {     // REQ-005
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("x.exec"), "");
        Files.writeString(execDir.resolve("x.json"),
                "{\"testId\":\"x\",\"classCount\":5,\"status\":\"complete\",\"durationMs\":12,\"result\":\"passed\"}");
        CoverageByPathReport.write(assetWithPath("p1", List.of("x")), out);
        JsonNode r = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")));
        JsonNode ef = r.get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("traceId").asText()).isEqualTo("x");
        assertThat(ef.get("exec").asText()).isEqualTo("work/pjacoco-exec/x.exec");
        assertThat(ef.get("sidecar").asText()).isEqualTo("work/pjacoco-exec/x.json");
        assertThat(r.get("paths").get(0).get("pathId").asText()).isEqualTo("p1");
    }

    @Test
    void projectsSidecarSummary(@TempDir Path out) throws Exception {          // REQ-006
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("x.exec"), "");
        Files.writeString(execDir.resolve("x.json"),
                "{\"testId\":\"x\",\"classCount\":5,\"status\":\"complete\",\"durationMs\":2065,\"result\":\"passed\"}");
        CoverageByPathReport.write(assetWithPath("p1", List.of("x")), out);
        JsonNode summary = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0).get("summary");
        assertThat(summary.get("classCount").asInt()).isEqualTo(5);
        assertThat(summary.get("durationMs").asLong()).isEqualTo(2065);
        assertThat(summary.get("result").asText()).isEqualTo("passed");
    }

    @Test
    void missingSidecarYieldsNullSummaryNoThrow(@TempDir Path out) throws Exception {   // REQ-007
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("y.exec"), "");   // 사이드카 없음
        CoverageByPathReport.write(assetWithPath("p1", List.of("y")), out);
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("summary").isNull()).isTrue();
    }

    @Test
    void malformedSidecarYieldsNullSummaryNoThrow(@TempDir Path out) throws Exception {  // REQ-007
        Path execDir = Files.createDirectories(out.resolve("work/pjacoco-exec"));
        Files.writeString(execDir.resolve("w.exec"), "");
        Files.writeString(execDir.resolve("w.json"), "{ this is not json");
        assertThatCode(() -> CoverageByPathReport.write(assetWithPath("p1", List.of("w")), out))
                .doesNotThrowAnyException();
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles").get(0);
        assertThat(ef.get("summary").isNull()).isTrue();
    }

    @Test
    void emptyTraceIdsYieldEmptyExecFiles(@TempDir Path out) throws Exception {
        Files.createDirectories(out.resolve("work/pjacoco-exec"));
        CoverageByPathReport.write(assetWithPath("p1", List.of()), out);
        JsonNode ef = Json.mapper().readTree(Files.readString(out.resolve("coverage-by-path.json")))
                .get("paths").get(0).get("execFiles");
        assertThat(ef).isEmpty();
    }
}
```

(주의: `GraphAsset` canonical 생성자 인자 순서/개수는 `shared-model/.../GraphAsset.java`를 열어 정확히 맞춘다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.coverage.CoverageByPathReportTest'`
Expected: 컴파일 FAIL(`CoverageByPathReport` 없음).

- [ ] **Step 3: 생성기 구현**

```java
package io.graphrag.builder.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REQ-005/006/007: graph 모델을 투영해 coverage-by-path.json을 쓴다. */
public final class CoverageByPathReport {

    private static final Logger log = LoggerFactory.getLogger(CoverageByPathReport.class);
    private static final String EXEC_REL = "work/pjacoco-exec";

    private CoverageByPathReport() {}

    public static void write(GraphAsset asset, Path outDir) {
        Path execDir = outDir.resolve(EXEC_REL);
        List<Map<String, Object>> paths = new ArrayList<>();
        for (ExploredPath p : asset.paths()) {
            List<Map<String, Object>> execFiles = new ArrayList<>();
            for (String tid : p.coverageTraceIds()) {
                Map<String, Object> ef = new LinkedHashMap<>();
                ef.put("traceId", tid);
                ef.put("exec", EXEC_REL + "/" + tid + ".exec");
                ef.put("sidecar", EXEC_REL + "/" + tid + ".json");
                ef.put("summary", readSummary(execDir.resolve(tid + ".json")));
                execFiles.add(ef);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pathId", p.id());
            entry.put("endpointId", p.endpointId());
            entry.put("coverageTraceIds", p.coverageTraceIds());
            entry.put("execFiles", execFiles);
            paths.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sutId", asset.sutId());
        root.put("execDir", EXEC_REL);
        root.put("paths", paths);
        try {
            Files.writeString(outDir.resolve("coverage-by-path.json"),
                    Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("coverage-by-path.json 작성 실패(무시): {}", e.toString());
        }
    }

    /** 사이드카에서 summary 투영. 부재·손상 시 null(throw 없음). */
    private static Map<String, Object> readSummary(Path sidecar) {
        if (!Files.exists(sidecar)) return null;
        try {
            JsonNode n = Json.mapper().readTree(Files.readString(sidecar));
            Map<String, Object> s = new LinkedHashMap<>();
            if (n.has("classCount")) s.put("classCount", n.get("classCount").asInt());
            if (n.hasNonNull("result")) s.put("result", n.get("result").asText());
            if (n.has("status")) s.put("status", n.get("status").asText());
            if (n.has("durationMs")) s.put("durationMs", n.get("durationMs").asLong());
            return s;
        } catch (Exception e) {
            log.warn("사이드카 파싱 실패 {} (summary=null): {}", sidecar, e.toString());
            return null;
        }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.coverage.CoverageByPathReportTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/coverage/CoverageByPathReport.java graph-rag-builder/src/test/java/io/graphrag/builder/coverage/CoverageByPathReportTest.java
git commit -m "feat(coverage): CoverageByPathReport 생성기 [REQ-005/006/007]"
```

---

### Task 12: BuilderCli에서 리포트 생성 배선 (가드 포함)

**REQ-IDs:** REQ-004, REQ-010

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (~364, `PartitionedGraphStore.save(asset)` 직후)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/CoverageReportWiringTest.java` (create)

**Interfaces:**
- Consumes: `CoverageByPathReport.write(asset, config.out())`.
- 가드: `work/pjacoco-exec` 디렉터리 존재 且 `.exec` ≥ 1.

- [ ] **Step 1: 실패 테스트 작성 (가드 단위)**

리포트 생성 가드를 작은 static 헬퍼 `shouldWriteCoverageReport(Path outDir)`로 두고 테스트:

```java
@Test
void noExecDirSkips(@TempDir Path out) {
    assertThat(BuilderCli.shouldWriteCoverageReport(out)).isFalse();
}
@Test
void emptyExecDirSkips(@TempDir Path out) throws Exception {
    Files.createDirectories(out.resolve("work/pjacoco-exec"));
    assertThat(BuilderCli.shouldWriteCoverageReport(out)).isFalse();
}
@Test
void execPresentTriggers(@TempDir Path out) throws Exception {
    Files.createDirectories(out.resolve("work/pjacoco-exec"));
    Files.writeString(out.resolve("work/pjacoco-exec/a.exec"), "");
    assertThat(BuilderCli.shouldWriteCoverageReport(out)).isTrue();
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.CoverageReportWiringTest'`
Expected: 컴파일 FAIL.

- [ ] **Step 3: 가드 헬퍼 + 배선**

`BuilderCli`에 추가:

```java
static boolean shouldWriteCoverageReport(Path outDir) {
    Path execDir = outDir.resolve("work/pjacoco-exec");
    if (!Files.isDirectory(execDir)) return false;
    try (var s = Files.newDirectoryStream(execDir, "*.exec")) {
        return s.iterator().hasNext();
    } catch (Exception e) {
        return false;
    }
}
```

`build(config)` 내 `new io.graphrag.builder.store.PartitionedGraphStore(config.out()).save(asset);` 직후(~364):

```java
if (shouldWriteCoverageReport(config.out())) {
    io.graphrag.builder.coverage.CoverageByPathReport.write(asset, config.out());
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.CoverageReportWiringTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java graph-rag-builder/src/test/java/io/graphrag/builder/cli/CoverageReportWiringTest.java
git commit -m "feat(cli): graph 저장 직후 coverage-by-path.json 생성(가드) [REQ-004/010]"
```

---

### Task 13: 전체 회귀 + E2E 확인 (안쪽 루프 종료)

**REQ-IDs:** REQ-001~015 (전체 green 확인)

**Files:** 없음(검증).

- [ ] **Step 1: 모듈 단위 테스트 전체**

Run: `./gradlew :shared-model:test :graph-rag-builder:test`
Expected: 전부 PASS. 신규 테스트(REQ-002/005/006/007/008/009/011/012/013/014/015) green.

- [ ] **Step 2: E2E (Docker 가용 시)**

Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.coverage.CoverageTraceMappingE2E'`
Expected: Docker 가용 시 PASS(REQ-001/003/004/006), 미가용 시 skip(명시 기록).

- [ ] **Step 3: 매트릭스 갱신**

`docs/superpowers/requirements/2026-06-25-path-coverage-trace-mapping-requirements.md`의 추적 매트릭스 Status를 실제 결과로 🟢/🟡 갱신, Coverage 줄 갱신(목표 15/15 green; E2E skip 시 그 사실 명시).

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/requirements/2026-06-25-path-coverage-trace-mapping-requirements.md
git commit -m "test: 전체 회귀 green + 추적 매트릭스 갱신 [REQ-001..015]"
```

---

### Task 14: 문서 갱신 (README + 스키마)

**REQ-IDs:** (문서 동기화 게이트)

**Files:**
- Modify: `README.md` (`<out>` 산출물 목록에 `coverage-by-path.json` 추가; `ExploredPath`/graph.json 스키마에 `coverageTraceIds` 추가)

- [ ] **Step 1: README 갱신**

`<out>` 산출물 섹션에 `coverage-by-path.json` 1줄 + 스키마 예시 추가. graph.json path 스키마 설명에 `coverageTraceIds: List<String>`(그 path를 정의한 대표 probe traceId; `work/pjacoco-exec/<traceId>.exec` 역참조) 추가. 비-HTTP/traceparent 미주입 path는 빈 리스트, exec 부재 시 리포트 미생성을 명시.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: coverage-by-path.json + coverageTraceIds 스키마 문서화"
```

---

## Self-Review

**1. Spec coverage (REQ 매핑):**
- REQ-001 → Task 7 (대표 path 적재) + Task 1 E2E
- REQ-002 → Task 4 + Task 5 (orchestrator)
- REQ-003 → Task 1 E2E
- REQ-004 → Task 12 + Task 1 E2E
- REQ-005 → Task 11
- REQ-006 → Task 11 + Task 1 E2E
- REQ-007 → Task 11 (missing/malformed/missing-exec)
- REQ-008 → Task 5 (null→빈 리스트, tie-break)
- REQ-009 → Task 6 (legacy/null 역직렬화)
- REQ-010 → Task 12 (가드)
- REQ-011 → Task 8 (copy 보존)
- REQ-012 → Task 10 (arm 누적)
- REQ-013 → Task 10 (egress-assertion 빈 리스트)
- REQ-014 → Task 9 (직접 생성 4사이트)
- REQ-015 → Task 2/3/6 (round-trip)
모든 REQ에 task 대응. 누락 없음.

**2. Placeholder scan:** 코드 스텝은 실제 코드 포함. `...` 표기는 "기존 인자 그대로 유지" 의미로만 사용(Task 7/9에서 명시). 신규 식별자(`coverageTraceIdsOf`, `collectArmTraceIds`, `shouldWriteCoverageReport`, `CoverageByPathReport.write`)는 정의 task에서 시그니처 제공.

**3. Type consistency:**
- `coverageTraceId`(단수, String, nullable): InvocationOutcome/VariantOutcome/PathCandidate.
- `coverageTraceIds`(복수, List<String>, non-null): ExploredPath + 리포트.
- `CoverageByPathReport.write(GraphAsset, Path)` — Task 11 정의, Task 12 호출 일치.
- exec 상대경로 규약 `work/pjacoco-exec/<tid>.exec` — Task 11/12/Task 1 E2E 일치.

**주의(구현자):** `GraphAsset`·각 레코드의 정확한 canonical 인자 순서는 해당 소스를 열어 맞춘다(이 plan의 생성자 호출 예시는 인자 개수·순서를 현 코드 기준으로 작성했으나, 구현 시 컴파일러로 최종 확인).
