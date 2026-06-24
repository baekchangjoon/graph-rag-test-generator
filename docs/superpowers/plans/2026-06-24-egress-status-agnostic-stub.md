# REQ-015 status-무관 stub register 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** redirect 없이 span으로 발견된 외부 HTTP 호출을 정적 인덱스에 매칭해 형상-시드 body로 생성 테스트 stub에 등록한다(빈 body → 형상 body).

**Architecture:** 빌더-측 enrichment(접근 A). 신규 순수 컴포넌트 `EgressStubComposer`가 `EgressCall`을 `CallSiteMatcher`로 매칭하고 `ShapeJsonSynthesizer`로 body를 합성하면, `EndpointExplorationRunner.captureHttpCalls`가 그 결과로 `CapturedHttpCall`을 조립한다. generator/testlib는 무변경(기존 `HttpMockComposer`가 비어있지 않은 body를 그대로 방출).

**Tech Stack:** Java 17 (repo toolchain, `build.gradle.kts:53` `JavaLanguageVersion.of(17)`), Gradle, JUnit 5, AssertJ, Jackson. 모듈: `graph-rag-builder`(빌더), `test-generator`(생성기), `shared-model`(`CapturedHttpCall`).

## Global Constraints

- 커밋 identity: `baekchangjoon <changjoon.baek@icloud.com>` (env vars `GIT_AUTHOR_*`/`GIT_COMMITTER_*`, 글로벌 config 수정 금지).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM`.
- worktree: `.claude/worktrees/feat-egress-status-agnostic-stub`, 브랜치 `worktree-feat-egress-status-agnostic-stub`. **모든 파일 작업은 이 worktree 절대경로 기준**(서브에이전트는 커밋 전 `git rev-parse --abbrev-ref HEAD`로 브랜치 확인).
- 코드네임 위생: 사내 코드네임을 코드·문서·커밋에 남기지 않는다(일반화 표현만).
- `EgressCallMapper.toCapturedHttpCall`의 단위 계약(항상 CAPTURED·빈 body)은 변경 금지 — fallback로 유지.
- 테스트는 외과적: 기존 green 테스트(`EgressCallMapperTest`, `EgressDiscoveryWiringTest`)를 깨지 않는다.
- 빌드/단위: `./gradlew :graph-rag-builder:test`, `:test-generator:test`.

---

### Task 1: 외부 루프 수용 테스트 작성 (RED, 약화 금지)

**REQ-IDs:** REQ-S015-001, REQ-S015-006

먼저 실패하는 수용 테스트를 둔다. 이 테스트들은 구현 전 **RED가 정상**이며, 이후 Task로 GREEN이 된다. 절대 약화·주석처리하지 않는다.

**Files:**
- Test (Create): `graph-rag-builder/src/test/java/io/graphrag/builder/run/CaptureHttpCallsEgressEnrichTest.java`
- Test (Create): `test-generator/src/test/java/io/graphrag/generator/compose/HttpMockComposerEgressTest.java`

**Interfaces:**
- Consumes: `EndpointExplorationRunner.captureHttpCalls(PathCandidate)` (private; reflection),
  `PathCandidate`, `EgressCall(method, path, statusOrNull, traceId, startNanos)`,
  `ExternalCallSite(String httpMethod, String pathLiteral, Optional<BodyShape> responseShape)`,
  `BodyShape(String javaType, List<BodyField>)`, `BodyShape.BodyField(name, javaType)`,
  `CapturedHttpCall`, `HttpMockComposer.compose(List<CapturedHttpCall>)`.
- Produces: 없음(테스트만).

- [ ] **Step 1: 빌더 통합 수용 테스트 작성 (matched → SYNTHESIZED non-empty body)**

`CaptureHttpCallsEgressEnrichTest.java`:

```java
package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.IntNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-S015-001/002/004/005: captureHttpCalls의 egress enrichment(형상-시드 stub) 검증. */
class CaptureHttpCallsEgressEnrichTest {

    private static ExternalCallSite siteWithStringField(String method, String path, String field) {
        BodyShape shape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField(field, "java.lang.String")));
        return new ExternalCallSite(method, path, Optional.of(shape));
    }

    private static EndpointExplorationRunner runner(
            List<ExternalCallSite> callSites, List<Set<String>> responseDtoFieldSets) {
        return new EndpointExplorationRunner(
                null, null, null, null, null, 0,
                /* httpCapture */ null,
                /* responseDtoFieldSets */ responseDtoFieldSets,
                /* literalCandidates */ List.of(),
                null, null,
                /* enumConstants */ Map.of(), /* enumColumns */ Map.of(),
                null, null, null, null,
                /* callSites */ callSites,
                /* egressCollector */ null);
    }

    private static PathCandidate candidate(List<EgressCall> egress) {
        return new PathCandidate("p1", IntNode.valueOf(0), 200, IntNode.valueOf(0),
                List.of(), "heuristic", 0, 0,
                /* httpExchanges */ List.of(),
                List.of(), List.of(), null, Map.of(),
                egress);
    }

    @SuppressWarnings("unchecked")
    private static List<CapturedHttpCall> capture(EndpointExplorationRunner runner, PathCandidate pc)
            throws Exception {
        Method m = EndpointExplorationRunner.class.getDeclaredMethod("captureHttpCalls", PathCandidate.class);
        m.setAccessible(true);
        return (List<CapturedHttpCall>) m.invoke(runner, pc);
    }

    @Test
    @DisplayName("REQ-S015-001: matched egress → SYNTHESIZED + 비어있지 않은 형상 body")
    void matchedEgressSynthesizesBody() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")),
                List.of(Set.of("type")));
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/inventory/stock", 200, "t", 1L))));

        assertThat(out).hasSize(1);
        CapturedHttpCall c = out.get(0);
        assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(c.responseBody()).isNotBlank();
        assertThat(c.responseBody()).contains("type");
        assertThat(c.consumedFields()).contains("type");          // REQ-S015-002
        assertThat(c.method()).isEqualTo("GET");
        assertThat(c.urlPath()).isEqualTo("/inventory/stock");
    }
}
```

이 테스트는 컴파일은 되지만 현재 구현에서 **assertion으로 RED**다(현재 `captureHttpCalls`는 빈-body CAPTURED를 만들어 `responseProvenance==SYNTHESIZED`·`responseBody` 비어있지 않음 단언이 실패). Task 3 배선으로 GREEN이 된다.

- [ ] **Step 2: generator 수용 테스트 작성 (REQ-S015-006 AC1)**

`HttpMockComposerEgressTest.java`:

```java
package io.graphrag.generator.compose;

import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-S015-006: 비어있지 않은 형상 body를 가진 egress CapturedHttpCall → 비어있지 않은 stub 방출. */
class HttpMockComposerEgressTest {

    @Test
    @DisplayName("REQ-S015-006: egress CapturedHttpCall → respondJson(비어있지 않은 body) 방출")
    void emitsNonEmptyStubBody() {
        CapturedHttpCall call = new CapturedHttpCall(
                "http-p1-egress-1", "p1", "GET", "/inventory/stock",
                Map.of(), null, 200, "{\"type\":\"sample\"}",
                List.of("type"), false, CapturedHttpCall.Provenance.SYNTHESIZED);

        HttpMockComposer.ComposedMocks mocks = new HttpMockComposer().compose(List.of(call));

        assertThat(mocks.block()).contains("scope.http().stub(\"GET\", \"/inventory/stock\")");
        assertThat(mocks.block()).contains(".respondJson(200,");
        assertThat(mocks.block()).contains("type");
        assertThat(mocks.block()).doesNotContain(".respondJson(200, \"\")");
    }
}
```

- [ ] **Step 3: 빌더 테스트가 RED인지 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*CaptureHttpCallsEgressEnrichTest*'`
Expected: FAIL — 현재 `captureHttpCalls`는 `EgressCallMapper.toCapturedHttpCall`로 빈-body CAPTURED를 만들므로 `responseProvenance==SYNTHESIZED`·`responseBody` 비어있지 않음 단언이 실패한다.

- [ ] **Step 4: generator 테스트 확인**

Run: `./gradlew :test-generator:test --tests '*HttpMockComposerEgressTest*'`
Expected: PASS(특성화) — `HttpMockComposer`는 이미 비어있지 않은 body를 방출한다. 이 테스트는 generator 측 계약을 고정한다(REQ-S015-006 AC1). PASS여도 정상.

- [ ] **Step 5: 커밋 (RED 수용 테스트)**

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/run/CaptureHttpCallsEgressEnrichTest.java \
        test-generator/src/test/java/io/graphrag/generator/compose/HttpMockComposerEgressTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "test(egress): REQ-015 outer-loop 수용 테스트(RED) — enrich+generator

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM"
```

---

### Task 2: `EgressStubComposer` 컴포넌트 (unit TDD)

**REQ-IDs:** REQ-S015-001, REQ-S015-003

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java`
- Test (Create): `graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressStubComposerTest.java`

**Interfaces:**
- Consumes: `CallSiteMatcher.match(String method, String urlPath, List<ExternalCallSite>)` → `Optional<ExternalCallSite>`,
  `ExternalCallSite.responseShape()` → `Optional<BodyShape>`, `ExternalCallSite.pathLiteral()`,
  `ShapeJsonSynthesizer.synthesizeBody(BodyShape)` → `JsonNode`(throws `ShapeJsonSynthesizer.UnsupportedShapeException`),
  `EndpointExplorationRunner.LoudFail(String reason, String target)`, `CapturedHttpCall.Provenance`.
- Produces: `EgressStubComposer.compose(EgressCall, List<ExternalCallSite>, ShapeJsonSynthesizer)` → `EgressStubComposer.Outcome(String responseBody, CapturedHttpCall.Provenance provenance, Optional<EndpointExplorationRunner.LoudFail> loudFail)`.

- [ ] **Step 1: 실패 단위 테스트 작성**

`EgressStubComposerTest.java`:

```java
package io.graphrag.builder.run;

import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EgressStubComposerTest {

    private static final ShapeJsonSynthesizer SHAPES =
            new ShapeJsonSynthesizer(java.util.Map.<String, java.util.List<String>>of());

    private static ExternalCallSite site(String method, String path, BodyShape shape) {
        return new ExternalCallSite(method, path, Optional.ofNullable(shape));
    }

    private static EgressCall call(String method, String path) {
        return new EgressCall(method, path, 200, "t", 1L);
    }

    @Test
    @DisplayName("REQ-S015-001: matched + shape → SYNTHESIZED, 비어있지 않은 body, loudFail 없음")
    void matchedSynthesizes() {
        BodyShape shape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("type", "java.lang.String")));
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", shape)), SHAPES);
        assertThat(o.provenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(o.responseBody()).contains("type").isNotBlank();
        assertThat(o.loudFail()).isEmpty();
    }

    @Test
    @DisplayName("REQ-S015-003: unmatched → CAPTURED, 빈 body, unmatched-external-call")
    void unmatchedLoudFails() {
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/other"),
                List.of(site("GET", "/inventory/stock",
                        new BodyShape("io.example.Resp", List.of()))), SHAPES);
        assertThat(o.provenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unmatched-external-call");
    }

    @Test
    @DisplayName("REQ-S015-003: matched no shape → CAPTURED, unwired-external-dep")
    void noShapeLoudFails() {
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", null)), SHAPES);
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unwired-external-dep");
    }

    @Test
    @DisplayName("REQ-S015-003: 합성 불가 형상 → CAPTURED, unsynthesizable-shape")
    void unsynthesizableLoudFails() {
        BodyShape bad = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("nested", "com.example.Nested")));
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", bad)), SHAPES);
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unsynthesizable-shape");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposerTest*'`
Expected: FAIL — `EgressStubComposer` 미존재(compile error).

- [ ] **Step 3: `EgressStubComposer` 구현**

`EgressStubComposer.java`:

```java
package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;

import java.util.List;
import java.util.Optional;

/**
 * span-발견 {@link EgressCall}을 인덱싱한 {@link ExternalCallSite}에 매칭해 형상-시드 응답 body를
 * 합성한다 (REQ-S015-001/003). 순수 함수 — 상태·로깅 없음. 로깅·수집은 호출자(captureHttpCalls)가 한다.
 */
final class EgressStubComposer {

    private EgressStubComposer() {
    }

    /** 합성 결과: 성공이면 형상 JSON·SYNTHESIZED, 실패면 ""·CAPTURED + 사유 loudFail. */
    record Outcome(String responseBody,
                   CapturedHttpCall.Provenance provenance,
                   Optional<EndpointExplorationRunner.LoudFail> loudFail) {
    }

    static Outcome compose(EgressCall e, List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes) {
        Optional<ExternalCallSite> site = CallSiteMatcher.match(e.method(), e.path(), callSites);
        if (site.isEmpty()) {
            return fail("unmatched-external-call", e.method() + " " + e.path());
        }
        Optional<BodyShape> shape = site.get().responseShape();
        if (shape.isEmpty()) {
            return fail("unwired-external-dep", e.method() + " " + e.path());
        }
        try {
            JsonNode body = shapes.synthesizeBody(shape.get());
            return new Outcome(body.toString(), CapturedHttpCall.Provenance.SYNTHESIZED, Optional.empty());
        } catch (ShapeJsonSynthesizer.UnsupportedShapeException ex) {
            return fail("unsynthesizable-shape", site.get().pathLiteral());
        }
    }

    private static Outcome fail(String reason, String target) {
        return new Outcome("", CapturedHttpCall.Provenance.CAPTURED,
                Optional.of(new EndpointExplorationRunner.LoudFail(reason, target)));
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposerTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressStubComposerTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(egress): EgressStubComposer — callSite 매칭→형상 body 합성 (REQ-S015-001/003)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM"
```

---

### Task 3: `EndpointExplorationRunner` 배선 (egressShapes 필드 + captureHttpCalls)

**REQ-IDs:** REQ-S015-001, REQ-S015-002, REQ-S015-004, REQ-S015-005

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (필드 추가 ~L196 근처; canonical 생성자 ~L338; `captureHttpCalls` egress 루프 ~L2079-2085)
- Test (Modify): `graph-rag-builder/src/test/java/io/graphrag/builder/run/CaptureHttpCallsEgressEnrichTest.java` (REQ-004/005 케이스 추가)

**Interfaces:**
- Consumes: `EgressStubComposer.compose(...)`(Task 2), 기존 `consumedFields(String)`, `EgressCallMapper.{toCapturedHttpCall, mergeDedup}`, `externalLoudFails`(필드), `log`.
- Produces: 변경된 `captureHttpCalls` 행위(매칭 egress → SYNTHESIZED 형상 body).

- [ ] **Step 1: `egressShapes` 필드 추가**

`private final ExternalStubSynthesizer stubSynthesizer;`(L196 근처) 아래에 추가:

```java
    private final ShapeJsonSynthesizer egressShapes;   // egress enrich용 (httpCapture==null에도 보유)
```

- [ ] **Step 2: canonical 생성자에서 초기화 + stubSynthesizer와 공유**

canonical 생성자에서 `this.egressShapes`를 먼저 만들고, `this.stubSynthesizer` 생성 시 **동일 인스턴스를
재사용**한다(설계 §4.2(a) "egress/404 양쪽이 공유"). 기존:

```java
        this.stubSynthesizer = httpCapture == null ? null
                : new ExternalStubSynthesizer(httpCapture,
                        new ShapeJsonSynthesizer(enumConstants == null ? Map.of() : enumConstants),
                        httpCapture.traceKey());
```

교체:

```java
        this.egressShapes = new ShapeJsonSynthesizer(enumConstants == null ? Map.of() : enumConstants);
        this.stubSynthesizer = httpCapture == null ? null
                : new ExternalStubSynthesizer(httpCapture, egressShapes, httpCapture.traceKey());
```

- [ ] **Step 3: `captureHttpCalls`의 egress 루프 교체**

기존(L2079-2085):

```java
        List<io.graphrag.model.CapturedHttpCall> egress = new ArrayList<>();
        int egressSeq = 0;
        for (io.graphrag.builder.capture.egress.EgressCall e : candidate.egressCalls()) {
            egress.add(io.graphrag.builder.capture.egress.EgressCallMapper.toCapturedHttpCall(
                    e, candidate.pathId(), ++egressSeq));
        }
        return io.graphrag.builder.capture.egress.EgressCallMapper.mergeDedup(calls, egress);
```

교체:

```java
        List<io.graphrag.model.CapturedHttpCall> egress = new ArrayList<>();
        int egressSeq = 0;
        for (io.graphrag.builder.capture.egress.EgressCall e : candidate.egressCalls()) {
            egressSeq++;
            if (callSites.isEmpty()) {
                // 정적 인덱스 없음 → 기존 빈-body CAPTURED 경로(loud-fail 노이즈 방지, REQ-S015-004).
                egress.add(io.graphrag.builder.capture.egress.EgressCallMapper.toCapturedHttpCall(
                        e, candidate.pathId(), egressSeq));
                continue;
            }
            EgressStubComposer.Outcome outcome = EgressStubComposer.compose(e, callSites, egressShapes);
            outcome.loudFail().ifPresent(lf -> {
                if (!externalLoudFails.contains(lf)) {   // 2-pass 중복 누적 방지(REQ-S015-005)
                    log.warn("{}: {}", lf.reason(), lf.target());
                    externalLoudFails.add(lf);
                }
            });
            if (outcome.responseBody().isEmpty()) {
                egress.add(io.graphrag.builder.capture.egress.EgressCallMapper.toCapturedHttpCall(
                        e, candidate.pathId(), egressSeq));
            } else {
                egress.add(new io.graphrag.model.CapturedHttpCall(
                        "http-" + candidate.pathId() + "-egress-" + egressSeq,
                        candidate.pathId(), e.method(), e.path(),
                        Map.of(), null,
                        e.statusOrNull() == null ? 200 : e.statusOrNull(),
                        outcome.responseBody(),
                        consumedFields(outcome.responseBody()),   // redirect 경로와 동일(REQ-S015-002)
                        false, outcome.provenance()));
            }
        }
        return io.graphrag.builder.capture.egress.EgressCallMapper.mergeDedup(calls, egress);
```

- [ ] **Step 4: 통합 테스트에 REQ-004/005 케이스 추가**

`CaptureHttpCallsEgressEnrichTest.java`에 추가:

```java
    @Test
    @DisplayName("REQ-S015-004: callSites 빈 → 기존 빈-body CAPTURED, loud-fail 없음")
    void emptyCallSitesKeepsLegacy() throws Exception {
        EndpointExplorationRunner runner = runner(List.of(), List.of());
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/anything", 200, "t", 1L))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(out.get(0).responseBody()).isEmpty();
        assertThat(loudFails(runner)).isEmpty();
    }

    @Test
    @DisplayName("REQ-S015-005: 2-pass 반복 호출에도 loud-fail 중복 누적 없음")
    void noDuplicateLoudFailsAcrossPasses() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")), List.of());
        PathCandidate pc = candidate(List.of(new EgressCall("GET", "/unmatched", 200, "t", 1L)));
        capture(runner, pc);
        capture(runner, pc);
        assertThat(loudFails(runner)).hasSize(1);
    }

    @Test
    @DisplayName("REQ-S015-002: collection(array) 형상 → consumedFields 빈, body는 비어있지 않은 array")
    void collectionShapeYieldsArrayBodyEmptyConsumed() throws Exception {
        BodyShape arrayShape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("type", "java.lang.String")), true);
        EndpointExplorationRunner runner = runner(
                List.of(new ExternalCallSite("GET", "/inventory/list", Optional.of(arrayShape))),
                List.of(Set.of("type")));
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/inventory/list", 200, "t", 1L))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(out.get(0).responseBody()).startsWith("[").isNotBlank();   // array JSON
        assertThat(out.get(0).consumedFields()).isEmpty();                    // array root → 투영 비활성
    }

    @Test
    @DisplayName("REQ-S015-005: redirect-exchange가 enriched egress보다 우선(dedup)")
    void redirectWinsOverEnrichedEgress() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")), List.of(Set.of("type")));
        // redirect exchange(CAPTURED) + 동일 (GET,/inventory/stock) egress
        io.graphrag.builder.explore.RawHttpExchange redirect =
                new io.graphrag.builder.explore.RawHttpExchange(
                        "GET", "/inventory/stock", Map.of(), null, 200, "{\"redirected\":true}", false, "");
        PathCandidate pc = new PathCandidate("p1", IntNode.valueOf(0), 200, IntNode.valueOf(0),
                List.of(), "heuristic", 0, 0,
                List.of(redirect), List.of(), List.of(), null, Map.of(),
                List.of(new EgressCall("GET", "/inventory/stock", 200, "t", 1L)));
        List<CapturedHttpCall> out = capture(runner, pc);
        // 같은 (method,urlPath) 1건만 — redirect(existing) 우선
        assertThat(out).filteredOn(c -> c.urlPath().equals("/inventory/stock")).hasSize(1);
        assertThat(out.get(0).responseBody()).contains("redirected");
    }

    @SuppressWarnings("unchecked")
    private static List<?> loudFails(EndpointExplorationRunner runner) throws Exception {
        java.lang.reflect.Field f = EndpointExplorationRunner.class.getDeclaredField("externalLoudFails");
        f.setAccessible(true);
        return (List<?>) f.get(runner);
    }
```

> `RawHttpExchange` 생성자 인자 순서는 기존 `EgressDiscoveryWiringTest`의 헬퍼와 동일하다
> (method, urlPath, query, requestBody, status, responseBody, baggagePresent, traceId 마지막 ""):
> 그 파일을 참조해 정확한 arity를 맞춘다.

- [ ] **Step 5: 통합 테스트 전체 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*CaptureHttpCallsEgressEnrichTest*' --tests '*EgressDiscoveryWiringTest*'`
Expected: PASS — enrich 케이스 GREEN + 기존 `EgressDiscoveryWiringTest`(redirect 우선 dedup) 회귀 없음.

- [ ] **Step 6: 커밋**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/run/CaptureHttpCallsEgressEnrichTest.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "feat(egress): captureHttpCalls egress enrichment 배선 (REQ-S015-001/002/004/005)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM"
```

---

### Task 4: full-pipeline E2E (조건부) + 자원 정리 게이트

**REQ-IDs:** REQ-S015-006, REQ-S015-007

기존 egress E2E는 `EgressCollector.collect`까지만 검증하므로 **확장이 아니라 신규 클래스**로 둔다. 외부 의존을 직접 URL(WireMock 치환 아님)로 두고 `BuilderCli.build`(otel/sleuth, `externalStubsDir=null`)→graph→(선택)generator를 돌려, redirect 없이 발견된 호출이 비어있지 않은 형상 body로 stub 등록됨을 단언한다.

**Files:**
- Test (Create): `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStatusAgnosticStubE2E.java`

**Interfaces:**
- Consumes: `BuilderCli.build(...)`(기존 `OtelEgressDiscoveryE2E`/`Stage1ExternalStubSynthesisE2E`의 호출 형태 준용), graph 로더(`FileGraphRagClient`), `HttpMockComposer`.
- Produces: 없음(테스트만).

- [ ] **Step 1: 기존 E2E 게이팅·기동 패턴 확인**

두 패턴을 결합한다(이 작업의 핵심 — 단일 기존 클래스로는 안 됨):
- **직접-URL 기동(redirect 없음)**: `OtelEgressDiscoveryE2E`는 `com.sun.net.httpserver.HttpServer`로
  호스트 stub을 띄우고 `EXTERNAL_INVENTORY_URL`을 그 직접 URL로 준다(`externalStubsDir=null`, WireMock
  치환 미사용). `@Tag("integration")` + `@EnabledIfSystemProperty(named="sut.jar", matches=".+")` +
  `@TestInstance(PER_CLASS)` + `@BeforeAll`/`@AfterAll` teardown.
- **graph까지 빌드**: `Stage1ExternalStubSynthesisE2E`는 `GraphAsset asset = BuilderCli.build(new
  BuildConfig(...))` 후 `asset.httpCalls()`에서 `CapturedHttpCall`을 꺼내 `responseProvenance`/
  `responseBody`를 단언한다.
  ⚠️ 단 Stage1은 `externalStubsDir`(WireMock)로 redirect를 쓴다 — 본 E2E는 **direct URL +
  `externalStubsDir=null`**로 둬야 한다(redirect 없이 egress span 경로만).

> `OtelEgressDiscoveryE2E`는 `EgressCollector.collect` 레벨까지만 검증하고 graph를 거치지 않는다.
> 본 E2E는 그 기동(direct URL) + Stage1의 `BuilderCli.build→GraphAsset.httpCalls()` 단언을 결합한다.

- [ ] **Step 2: 신규 E2E 작성 (otel) — 골격**

```java
@Tag("integration")
@EnabledIfSystemProperty(named = "sut.jar", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EgressStatusAgnosticStubE2E {
    private HttpServer inventoryStub;     // OtelEgressDiscoveryE2E와 동일하게 127.0.0.1 호스트 stub

    @BeforeAll void up() throws Exception { /* inventoryStub 기동: GET /inventory/stock → 200 + JSON */ }

    @Test
    @DisplayName("REQ-S015-006: otel redirect 없이 발견된 GET /inventory/stock이 SYNTHESIZED 형상 body로 graph 기록")
    void otelEgressBecomesSynthesizedStub() throws Exception {
        // BuildConfig: trace mode otel, externalStubsDir=null, sutEnvTemplate에 EXTERNAL_INVENTORY_URL=직접 URL.
        //   인자 형태는 Stage1ExternalStubSynthesisE2E#build(Path)의 BuildConfig(...) 호출을 참조해 맞춘다.
        GraphAsset asset = BuilderCli.build(/* BuildConfig: otel, externalStubsDir=null, direct URL */);
        CapturedHttpCall inv = asset.httpCalls().stream()
                .filter(c -> c.urlPath().endsWith("/inventory/stock"))
                .findFirst().orElseThrow();
        assertThat(inv.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(inv.responseBody()).isNotBlank();
    }

    @AfterAll void down() { if (inventoryStub != null) inventoryStub.stop(0); /* + SUT/도커 teardown */ }
}
```

> `BuildConfig`의 정확한 인자는 `Stage1ExternalStubSynthesisE2E#build(Path)`(L143 `BuilderCli.build(new
> BuildConfig(... 60, null, externalStubsDir, ...))`)를 그대로 참조해 채운다 — 단 trace mode otel,
> `externalStubsDir=null`, 외부 URL은 호스트 stub 직접 URL. 환경 미충족(sut.jar 없음) 시 skip되며,
> **그 경우 REQ-S015-006의 1차 outer loop는 Task 1·3의 in-process integration이 담당**한다(PR 본문에 명시).

- [ ] **Step 3: 신규 E2E 작성 (sleuth)**

order-web을 sleuth egress 모드로 기동(`-Dsut.egress.sleuth=true`·`order.web.src`), `POST /orders` 탐색 → `(POST, /reservations)`가 graph에 SYNTHESIZED 비어있지 않은 body로 기록됨을 단언. 동일 게이트.

- [ ] **Step 4: 자원 정리/누수 게이트 (REQ-S015-007)**

`@AfterAll` try/finally로 이 테스트가 띄운 SUT 프로세스 PID·도커 compose(고유 project)만 teardown(`docker compose -p <uniq> down -v --remove-orphans` / PID kill). 종료 후 `docker ps -a --filter ...`·PID로 잔존 0 검증. `docker system prune`·광범위 `pkill` 금지.

- [ ] **Step 5: 실행(가능 시)·커밋**

Run(인프라 가용 시): `./gradlew :graph-rag-builder:test --tests '*EgressStatusAgnosticStubE2E*' -Dsut.jar=... [-Dsut.egress.sleuth=true ...]`
Expected: PASS 또는 게이트 skip. skip이면 그 사실 기록.

```bash
git add graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStatusAgnosticStubE2E.java
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "test(egress): REQ-015 full-pipeline E2E(조건부) + 자원 정리 (REQ-S015-006/007)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM"
```

---

### Task 5: 선행 요구사항·문서 정합성 갱신 + 회귀

**REQ-IDs:** REQ-S015-008

**Files:**
- Modify: `docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md` (REQ-005 AC 정정, REQ-015 deferred→in-scope)
- Modify: `docs/superpowers/specs/2026-06-24-egress-span-capture-design.md` (§2/§8 빈-body 서술 최신화)
- Modify: `docs/03-graph-rag-builder.md` (egress "발견까지"→"형상-시드 stub 등록까지")
- Modify: `docs/superpowers/requirements/2026-06-24-egress-status-agnostic-stub-requirements.md` (추적 매트릭스 🔴→🟢 갱신)

- [ ] **Step 1: egress REQ-005 AC 정정**

`2026-06-24-egress-span-capture-requirements.md`의 REQ-005 수용기준에 한 줄 추가: "단, `captureHttpCalls` enrichment 경로에서 callSite 매칭 성공 시 `responseProvenance=SYNTHESIZED`·형상 body로 기록한다(REQ-015, `2026-06-24-egress-status-agnostic-stub-requirements.md`). `EgressCallMapper.toCapturedHttpCall` 단위 계약(CAPTURED·빈 body)은 fallback로 유지." REQ-015 행을 `🔵 deferred`→`🟢`(또는 in-scope 링크)로 갱신.

- [ ] **Step 2: egress design §2/§8 + docs/03 최신화**

`2026-06-24-egress-span-capture-design.md` §2(범위)·§8에 "1순위는 발견·빈 body까지; 형상-시드 stub 등록은 REQ-015에서 추가됨" 한 줄. `docs/03-graph-rag-builder.md`의 egress 문단을 "발견 + 형상-시드 stub 등록"으로 갱신.

- [ ] **Step 3: 본 요구사항명세 추적 매트릭스 갱신**

`2026-06-24-egress-status-agnostic-stub-requirements.md`의 매트릭스에서 통과한 REQ를 🔴→🟢로, Coverage 줄을 실측으로 갱신(E2E 조건부 skip이면 그 REQ는 integration 근거로 green 처리하고 비고에 명시).

- [ ] **Step 4: 전체 회귀 + 누수 게이트**

Run: `./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test`
Expected: 전체 PASS(신규 + 기존). E2E가 컨테이너/프로세스를 띄웠다면 종료 후 잔존 0 확인.

- [ ] **Step 5: 커밋**

```bash
git add docs/
GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com \
GIT_COMMITTER_NAME=baekchangjoon GIT_COMMITTER_EMAIL=changjoon.baek@icloud.com \
git commit -m "docs(egress): REQ-015 구현 반영 — REQ-005 정정·REQ-015 활성화·docs/03 (REQ-S015-008)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RZgVNMM7hvq6sAvEQhPXoM"
```

---

## 완료 정의 (DoD)

- 요구사항명세 추적 매트릭스 대상(Must 8개) 100% green(E2E 조건부 skip은 integration 근거로 충족 + 비고 명시).
- `:graph-rag-builder:test` `:test-generator:test` `:shared-model:test` 전체 green, 기존 테스트 회귀 0.
- 자원 누수 0(E2E teardown 게이트).
- 영향 문서 갱신(Task 5) + PR 본문에 변경·문서 갱신 요약.
- PR 전: spec-compliance 리뷰 + code-quality 리뷰(pr-review-toolkit:code-reviewer) triage 완료.
