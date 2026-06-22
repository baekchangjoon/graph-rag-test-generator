# 성공 오라클 + 에러 엔벨로프 대응 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빌더가 HTTP 200으로 위장된 에러 엔벨로프를 FAILURE로 정확히 분류하고, generator가 에러 계약을 강하게 단언하며, GET-by-id에서 genuine SUCCESS path에 도달하게 한다.

**Architecture:** 교체가능 `ResponseClassifier`(InputOracle 패턴)를 도입해 status-only 판정을 outcome 기반으로 전환. 와이어 status는 보존하고 outcome/semanticStatus를 `ExploredPath` 추가 필드로 기록. generator는 outcome으로 단언을 분기. RC-B는 FAILURE를 피드백으로 pass-2 재시드를 예산 내 재시도.

**Tech Stack:** Java 23, Gradle, Jackson, JUnit5, REST Assured(생성 테스트), Mustache(템플릿), ASM/Z3(기존 InputOracle).

## Global Constraints

- 출처: docs/superpowers/specs/2026-06-22-success-oracle-error-envelope-design.md, requirements/2026-06-22-success-oracle-error-envelope-requirements.md
- 와이어 status 위조 금지 — `ExploredPath.expectedStatus`는 항상 실제 HTTP status.
- 기본 classifier = `StatusOnlyClassifier`(설정 미지정 시) → 비-엔벨로프 SUT 무영향(REQ-001).
- record 신규 필드는 compat 생성자로 후방호환(누락 시 기본값 흡수).
- 커밋 author/committer: `baekchangjoon <changjoon.baek@icloud.com>` (commit별 env vars).
- 각 task: 실패 테스트 먼저(red) → 최소 구현(green) → 커밋. REQ-ID는 task 헤더 아래 명시.

---

### Task 1: Outcome 모델 + ResponseClassifier 인터페이스 + StatusOnlyClassifier

**REQ-IDs:** REQ-001

**Files:**
- Create: `shared-model/src/main/java/io/graphrag/model/Outcome.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/ResponseClassifier.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/StatusOnlyClassifier.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/StatusOnlyClassifierTest.java`

**Interfaces:**
- Produces: `Outcome { Kind kind, int semanticStatus, String semanticStatusText, String signal }`, enum `Outcome.Kind { SUCCESS, FAILURE }`; `ResponseClassifier.classify(int wireStatus, JsonNode body) -> Outcome`; `StatusOnlyClassifier` (no-arg).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.oracle;

import io.graphrag.model.Outcome;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusOnlyClassifierTest {
    private final ResponseClassifier c = new StatusOnlyClassifier();

    @Test void twoxxIsSuccess() {
        Outcome o = c.classify(200, Json.mapper().createObjectNode());
        assertThat(o.kind()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(o.semanticStatus()).isEqualTo(200);
    }
    @Test void nonTwoxxIsFailure() {
        assertThat(c.classify(404, null).kind()).isEqualTo(Outcome.Kind.FAILURE);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*StatusOnlyClassifierTest'` → FAIL(컴파일 에러: Outcome/ResponseClassifier 없음).

- [ ] **Step 3: 최소 구현**

```java
// shared-model/.../Outcome.java
package io.graphrag.model;
public record Outcome(Kind kind, int semanticStatus, String semanticStatusText, String signal) {
    public enum Kind { SUCCESS, FAILURE }
    public static Outcome success(int status) { return new Outcome(Kind.SUCCESS, status, String.valueOf(status), "status"); }
}
```
```java
// graph-rag-builder/.../oracle/ResponseClassifier.java
package io.graphrag.builder.oracle;
import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;
public interface ResponseClassifier { Outcome classify(int wireStatus, JsonNode body); }
```
```java
// graph-rag-builder/.../oracle/StatusOnlyClassifier.java
package io.graphrag.builder.oracle;
import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;
public final class StatusOnlyClassifier implements ResponseClassifier {
    @Override public Outcome classify(int wireStatus, JsonNode body) {
        return wireStatus / 100 == 2
            ? Outcome.success(wireStatus)
            : new Outcome(Outcome.Kind.FAILURE, wireStatus, String.valueOf(wireStatus), "status");
    }
}
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 5: 커밋** — `feat(builder): Outcome 모델 + ResponseClassifier + StatusOnlyClassifier`

---

### Task 2: ErrorEnvelopeClassifier (presence AND non-null, OR, semanticStatus 복원)

**REQ-IDs:** REQ-002, REQ-003

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/ErrorEnvelopeClassifier.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/oracle/ErrorEnvelopeClassifierTest.java`

**Interfaces:**
- Consumes: `Outcome`, `ResponseClassifier`.
- Produces: `new ErrorEnvelopeClassifier(List<String> triggerFields, String statusField)`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.oracle;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Outcome;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorEnvelopeClassifierTest {
    private final ResponseClassifier c = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");

    @Test void enveloped200IsFailureAndRecoversStatus() {
        ObjectNode b = Json.mapper().createObjectNode();
        b.put("errorCode", "404"); b.put("errorDetail", "...BizException...");
        Outcome o = c.classify(200, b);
        assertThat(o.kind()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(o.semanticStatusText()).isEqualTo("404");
        assertThat(o.semanticStatus()).isEqualTo(404);
    }
    @Test void successFieldNullStaysSuccess() {
        ObjectNode b = Json.mapper().createObjectNode(); b.putNull("errorCode");
        assertThat(c.classify(200, b).kind()).isEqualTo(Outcome.Kind.SUCCESS);
    }
    @Test void absentTriggerStaysSuccess() {
        assertThat(c.classify(200, Json.mapper().createObjectNode()).kind()).isEqualTo(Outcome.Kind.SUCCESS);
    }
    @Test void unparseableStatusKeepsWireStatus() {
        ObjectNode b = Json.mapper().createObjectNode(); b.put("errorCode", "X");
        Outcome o = c.classify(200, b);
        assertThat(o.kind()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(o.semanticStatus()).isEqualTo(200);
        assertThat(o.semanticStatusText()).isEqualTo("X");
    }
}
```

- [ ] **Step 2: 실패 확인** — FAIL(클래스 없음).

- [ ] **Step 3: 최소 구현**

```java
package io.graphrag.builder.oracle;
import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;
import java.util.List;

public final class ErrorEnvelopeClassifier implements ResponseClassifier {
    private final List<String> triggerFields;
    private final String statusField;
    public ErrorEnvelopeClassifier(List<String> triggerFields, String statusField) {
        this.triggerFields = triggerFields; this.statusField = statusField;
    }
    @Override public Outcome classify(int wireStatus, JsonNode body) {
        if (body == null || !isError(body)) return Outcome.success(wireStatus);
        String text = body.hasNonNull(statusField) ? body.get(statusField).asText() : String.valueOf(wireStatus);
        int semantic = wireStatus;
        try { semantic = Integer.parseInt(text.trim()); } catch (NumberFormatException ignored) {}
        return new Outcome(Outcome.Kind.FAILURE, semantic, text, "envelope:" + statusField);
    }
    private boolean isError(JsonNode body) {
        for (String f : triggerFields) {
            JsonNode v = body.get(f);
            if (v != null && !v.isNull() && !v.asText().isEmpty()) return true;  // 존재 AND non-null AND non-empty (OR)
        }
        return false;
    }
}
```

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(builder): ErrorEnvelopeClassifier (presence+non-null OR, semanticStatus 복원)`

---

### Task 3: ClassifierConfig + CLI 플래그 + BuildConfig 주입

**REQ-IDs:** REQ-011

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/oracle/ClassifierConfig.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuildConfig.java` (필드 추가 + compat 생성자)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (플래그 파싱 + classifier 생성)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/ClassifierConfigParseTest.java`

**Interfaces:**
- Produces: `ClassifierConfig { List<String> errorWhenPresent, String semanticStatusField, String errorDetailField, String errorDetailContains }`; `ClassifierConfig.from(Map<String,String> options)`; `ClassifierConfig.toClassifier() -> ResponseClassifier` (errorWhenPresent 비면 StatusOnlyClassifier).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.cli;
import io.graphrag.builder.oracle.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ClassifierConfigParseTest {
    @Test void noFlagsYieldStatusOnly() {
        ClassifierConfig cfg = ClassifierConfig.from(Map.of());
        assertThat(cfg.toClassifier()).isInstanceOf(StatusOnlyClassifier.class);
    }
    @Test void errorWhenPresentYieldsEnvelopeClassifier() {
        ClassifierConfig cfg = ClassifierConfig.from(Map.of("--error-when-present", "errorCode"));
        assertThat(cfg.toClassifier()).isInstanceOf(ErrorEnvelopeClassifier.class);
        assertThat(cfg.semanticStatusField()).isEqualTo("errorCode");  // 기본값
    }
}
```

- [ ] **Step 2: 실패 확인** — FAIL.

- [ ] **Step 3: 최소 구현**

```java
// ClassifierConfig.java
package io.graphrag.builder.oracle;
import java.util.List;
import java.util.Map;
public record ClassifierConfig(List<String> errorWhenPresent, String semanticStatusField,
                               String errorDetailField, String errorDetailContains) {
    public static ClassifierConfig from(Map<String, String> opts) {
        String when = opts.get("--error-when-present");
        List<String> fields = (when == null || when.isBlank())
            ? List.of() : List.of(when.split(","));
        String statusField = opts.getOrDefault("--semantic-status-field", "errorCode");
        return new ClassifierConfig(fields, statusField,
            opts.get("--error-detail-field"), opts.get("--error-detail-contains"));
    }
    public ResponseClassifier toClassifier() {
        return errorWhenPresent.isEmpty()
            ? new StatusOnlyClassifier()
            : new ErrorEnvelopeClassifier(errorWhenPresent, semanticStatusField);
    }
}
```
`BuildConfig`에 `ClassifierConfig classifierConfig` 필드 추가 + 기존 인자 compat 생성자(누락 시 `ClassifierConfig.from(Map.of())`). `BuilderCli`에서 옵션 맵으로 `ClassifierConfig.from(options)`를 만들어 `BuildConfig`에 전달.

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(builder): ClassifierConfig + CLI 플래그(--error-when-present 등) 주입`

---

### Task 4: ExploredPath outcome 필드 + compat 생성자

**REQ-IDs:** REQ-004

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/ExploredPath.java`
- Test: `shared-model/src/test/java/io/graphrag/model/ExploredPathCompatTest.java`

**Interfaces:**
- Produces: `ExploredPath`에 `Outcome.Kind outcome`, `int semanticStatus`, `String semanticStatusText` 추가; 기존 인자 compat 생성자(누락 시 `outcome=SUCCESS, semanticStatus=expectedStatus`).

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.model;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathCompatTest {
    @Test void legacyConstructorDefaultsToSuccess() {
        ExploredPath p = new ExploredPath("id","ep",null,200,null,
            List.of(),List.of(),List.of(),"heuristic",List.of(),List.of(),List.of(),List.of(),java.util.Map.of());
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(p.semanticStatus()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: 실패 확인** — FAIL(`outcome()` 없음).

- [ ] **Step 3: 최소 구현** — `ExploredPath` 레코드에 3개 필드를 canonical에 추가하고, 기존 14-arg 생성자를 compat 생성자로 보존(신규 필드 기본값 위임). 기존 호출부는 이 compat 생성자로 컴파일 유지.

- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(model): ExploredPath outcome/semanticStatus 필드 + compat 생성자`

---

### Task 5: EndpointExplorationRunner — classifier 배선 (outcome 기록, 와이어 status 보존)

**REQ-IDs:** REQ-002, REQ-004

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (classifier 주입, `InvocationOutcome`/`PathCandidate`에 outcome 전파, `buildPaths`에서 ExploredPath에 outcome 설정)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EnvelopeOutcomeWiringTest.java`

**Interfaces:**
- Consumes: `ResponseClassifier`(생성자 주입), `Outcome`, `ExploredPath`(신규 필드).
- Produces: enveloped-200 path의 `expectedStatus==200 && outcome==FAILURE && semanticStatusText` 보존.

- [ ] **Step 1: 실패 테스트 작성** — `ErrorEnvelopeClassifier`를 주입한 Runner가 enveloped-200 응답에서 `ExploredPath.outcome==FAILURE`, `expectedStatus==200`을 산출하는지 검증(기존 Runner 단위 테스트 픽스처 패턴 차용; 외부 호출은 stub invoker).

```java
// 핵심 단언 (픽스처는 기존 Runner 테스트 헬퍼 재사용)
ExploredPath p = paths.stream().filter(x -> x.outcome()==Outcome.Kind.FAILURE).findFirst().orElseThrow();
assertThat(p.expectedStatus()).isEqualTo(200);
assertThat(p.semanticStatusText()).isEqualTo("404");
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — Runner 생성자에 `ResponseClassifier` 추가(기본 `StatusOnlyClassifier`), `recordOutcome`(InvocationOutcome 생성, :1383 부근)에서 `classifier.classify(statusCode, body)` 호출해 outcome 보관, `PathCandidate`→`ExploredPath` 변환(:1142 부근)에서 outcome/semanticStatus 설정. **`expectedStatus`는 와이어 status 그대로.**
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(builder): classifier 배선 — outcome 기록, 와이어 status 보존`

---

### Task 6: 파이프라인 outcome-gating (fuzzer·attachSeeds·Kafka·dedup/path-id)

**REQ-IDs:** REQ-005

**Files:**
- Modify: `graph-rag-builder/.../explore/CoverageGuidedFuzzer.java:38,59-60`
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java:889,1168-1171`
- Modify: `graph-rag-builder/.../explore/ExplorationOrchestrator.java:61,83`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/explore/OutcomeGatingTest.java`

**Interfaces:**
- Consumes: `Outcome`.
- Produces: 시드 정렬·seed 부착·Kafka happy·dedup 키/path-id가 outcome 기준; genuine-200과 enveloped-200(FAILURE)은 path-id(`-s200e404-`)·dedup 키(`+kind`)로 분리.

- [ ] **Step 1: 실패 테스트** — (a) 시드 큐 정렬이 SUCCESS-first(enveloped-200 시드가 genuine-200보다 뒤), (b) dedup 키가 동일 coverage라도 SUCCESS/FAILURE를 분리, (c) FAILURE path-id에 semanticStatus 포함.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — 각 지점의 `status/100==2` → `outcome.kind==SUCCESS`. `addSeed`에 outcome 전달. `ExplorationOrchestrator` 키 = `outcome.kind + ":" + status + ":" + cov`, path-id = FAILURE면 `-s{status}e{semanticStatus}-`.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `fix(builder): success 판정 outcome-gating (fuzzer/seed/kafka/dedup)`

---

### Task 7: FixtureComposer.lookupSucceeded outcome 게이트

**REQ-IDs:** REQ-005

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java:299`
- Test: `test-generator/src/test/java/io/graphrag/generator/compose/LookupSucceededOutcomeTest.java`

**Interfaces:**
- Consumes: `ExploredPath.outcome()`.

- [ ] **Step 1: 실패 테스트** — enveloped-200(outcome=FAILURE) path에 `lookupSucceeded`가 false → 잘못된 seed INSERT 미생성.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — `return path.expectedStatus()/100==2;` → `return path.outcome() == Outcome.Kind.SUCCESS;` (구 그래프 호환: outcome null이면 status 폴백).
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `fix(generator): lookupSucceeded를 outcome 기준으로`

---

### Task 8: Generator.postCreateCleanup outcome 게이트

**REQ-IDs:** REQ-005

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java:686`
- Test: `test-generator/src/test/java/io/graphrag/generator/PostCreateCleanupGatedTest.java`

- [ ] **Step 1: 실패 테스트** — enveloped-200 POST(outcome=FAILURE)에 cleanup 로직 미주입.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — `expectedStatus < 200 || expectedStatus >= 300` 게이트를 `outcome != SUCCESS`로 보강(POST 조건 유지, null 폴백).
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `fix(generator): postCreateCleanup을 outcome 기준으로`

---

### Task 9: verifyAndFilterNonTwoxx — error-envelope 마커 KEEP

**REQ-IDs:** REQ-007

**Files:**
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java:1787 verifyAndFilterNonTwoxx` + outcome=FAILURE path에 `discoveredBy="error-envelope"` 부여(buildPaths)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/VerifyAndFilterEnvelopeKeepTest.java`

- [ ] **Step 1: 실패 테스트** — enveloped-200 path가 DROP되지 않고 `discoveredBy=="error-envelope"`.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — `verifyAndFilterNonTwoxx`에서 `discoveredBy.startsWith("error-envelope")`도 무조건 KEEP(negative-* 분기 옆). buildPaths에서 FAILURE & 와이어-2xx면 marker 설정.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(builder): error-envelope path 마커 + 필터 KEEP`

---

### Task 10: ExplorationReport noHappyPathReason

**REQ-IDs:** REQ-008

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/ExplorationReport.java` (필드 추가 + compat)
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java` (모든 응답 FAILURE면 사유 기록)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/NoHappyPathReportTest.java`

- [ ] **Step 1: 실패 테스트** — 모든 응답 enveloped-200인 엔드포인트의 report에 `noHappyPathReason=="all responses error-enveloped"`.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — `EndpointExploration`에 `String noHappyPathReason`(nullable) 추가(compat 생성자), Runner가 SUCCESS path 0이고 FAILURE>0이면 사유 설정.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(model): ExplorationReport.noHappyPathReason`

---

### Task 11: Generator 에러 계약 단언 분기

**REQ-IDs:** REQ-006

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/compose/FixtureComposer.java:206-246` (outcome 분기)
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java` (errorDetailField/Contains 전달)
- Test: `test-generator/src/test/java/io/graphrag/generator/compose/ErrorContractAssertionTest.java`

**Interfaces:**
- Consumes: `ExploredPath.outcome()`, `semanticStatusText()`, ClassifierConfig의 errorDetailField/Contains.
- Produces: FAILURE path → `.body("<statusField>", equalTo("<semanticStatusText>"))` + (설정 시) `.body("<detailField>", org.hamcrest.Matchers.containsString("<substr>"))`.

- [ ] **Step 1: 실패 테스트**

```java
// 핵심 단언: 생성된 assertion 문자열에 포함
assertThat(assertions).anyMatch(a -> a.jsonPath().equals("errorCode") && a.matcher().equals("equalTo(\"404\")"));
assertThat(assertions).anyMatch(a -> a.matcher().contains("org.hamcrest.Matchers.containsString(\"BizException\")"));
// notNullValue-only 아님
assertThat(assertions).anyMatch(a -> a.matcher().startsWith("equalTo"));
```

- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — `composeAssertions`에서 `path.outcome()==FAILURE`면 에러 계약 단언 생성: statusField는 `equalTo("<semanticStatusText>")`(문자열), detail은 FQN `org.hamcrest.Matchers.containsString(...)`(템플릿 import 무변경). SUCCESS면 기존 결정성 로직.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(generator): outcome=FAILURE 에러 계약 단언(errorCode equalTo str + errorDetail containsString)`

---

### Task 12: RC-B — FAILURE 피드백 재탐색 (pass-2 재시드, 예산 N=4)

**REQ-IDs:** REQ-009

**Files:**
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java:254-301` (pass-2 트리거를 outcome=FAILURE GET-by-id로 확장, 예산 N=4 루프)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/RcbRetryLoopTest.java`

**Interfaces:**
- Consumes: `Outcome`, `SqlSeedResolver`, `ReadInputSynthesizer`.
- Produces: GET-by-id에서 outcome=FAILURE면 pass-2 재시드를 최대 4회 시도; 성공 path 발견 시 중단.

- [ ] **Step 1: 실패 테스트** — stub invoker가 3회째 유효 시드에서 비-엔벨로프 200을 반환하도록 구성 → 루프가 첫 FAILURE에서 안 멈추고 SUCCESS 도달, 시도 ≤4.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 최소 구현** — pass-2 조건에 `happyOutcome.kind==FAILURE` 추가, `for (attempt < 4 && lastOutcome==FAILURE)` 재시드 루프, 예산 상수 `RCB_RETRY_BUDGET=4`.
- [ ] **Step 4: 통과 확인** — PASS.
- [ ] **Step 5: 커밋** — `feat(builder): RC-B FAILURE 피드백 pass-2 재시드(예산 4)`

---

### Task 13: 샘플 SUT error-envelope-service

**REQ-IDs:** REQ-010 (인프라)

**Files:**
- Create: `samples/error-envelope-service/` (build.gradle.kts, GET /items/{id} 컨트롤러, `@RestControllerAdvice` BizException→200+엔벨로프 핸들러, `items` 테이블 schema/seed, application.yml)
- Test: 모듈 부트 스모크(기존 sample 패턴 차용)

- [ ] **Step 1: 실패 테스트** — 모듈 빌드/부트 스모크가 GET /items/{유효id}→비엔벨로프 200, /items/{없는id}→200+`{errorCode:"404", errorDetail:"...BizException..."}` 반환 검증.
- [ ] **Step 2: 실패 확인** — FAIL(모듈 없음).
- [ ] **Step 3: 최소 구현** — 최소 Spring Boot 모듈: `ItemController`, `GlobalExceptionHandler`(BizException), `items`(id PK + name), `data.sql`로 1행 시드.
- [ ] **Step 4: 통과 확인** — 스모크 PASS.
- [ ] **Step 5: 커밋** — `test(sample): error-envelope-service (BizException→200 엔벨로프)`

---

### Task 14: E2E 수용 (AC1~AC4)

**REQ-IDs:** REQ-001, REQ-002, REQ-006, REQ-010

**Files:**
- Create: `e2e/run-error-envelope-e2e.sh` (builder attach `--error-when-present errorCode --error-detail-field errorDetail --error-detail-contains BizException` → graph.json → generator → 컴파일·실행)
- Modify: 기존 회귀 확인(`e2e/run-e2e.sh`, `run-gateway-e2e.sh`, `run-legacy-tram-sleuth-e2e.sh`) — 무변경 GREEN

- [ ] **Step 1: E2E 작성(red)** — AC1: graph.json에서 enveloped path `outcome==FAILURE && expectedStatus==200 && semanticStatusText=="404"`. AC2: 생성 테스트에 `.statusCode(200)` + `.body("errorCode", equalTo("404"))` + `containsString("BizException")`. AC3b: genuine SUCCESS path ≥1 + branch coverage > 베이스라인. AC4: 기존 3개 e2e GREEN.
- [ ] **Step 2: 실패 확인** — Task 1~13 미완 시 red.
- [ ] **Step 3: 구현 드라이브** — Task 1~13으로 green.
- [ ] **Step 4: 통과 확인** — `./e2e/run-error-envelope-e2e.sh` PASS + 회귀 3종 PASS.
- [ ] **Step 5: 커밋** — `test(e2e): error-envelope 수용(AC1~AC4)`

---

## Self-Review

**1. Spec coverage:**
- §3.1 classifier → Task 1,2,3,11(detail config). §3.2 8개 지점 → Task 5,6,7,8. §3.3 dedup/path-id → Task 6. §3.4 필터/마커 → Task 9. §3.5 report → Task 10. §4 RC-B → Task 12. §5 generator → Task 11. §6 샘플/E2E → Task 13,14. §7 모델 변경 → Task 4,10. 누락 없음.
- REQ 매핑: REQ-001(T1,14), 002(T2,5,14), 003(T2), 004(T4,5), 005(T6,7,8), 006(T11,14), 007(T9), 008(T10), 009(T12), 010(T13,14), 011(T3). 전 REQ 매핑됨.

**2. Placeholder scan:** 코드 단계엔 실제 코드 포함. Task 5/6/9/12는 기존 대형 파일(EndpointExplorationRunner) 수정이라 핵심 단언·치환 규칙을 명시(전체 메서드 재현은 파일 규모상 라인 지정 + 치환 규칙으로 대체).

**3. Type consistency:** `Outcome.Kind`(SUCCESS/FAILURE), `outcome()/semanticStatus()/semanticStatusText()`, `ResponseClassifier.classify(int,JsonNode)`, `ClassifierConfig.toClassifier()` 명명이 Task 전반 일관.

## 의존 순서

T1 → T2 → T3, T4(병렬 가능) → T5 → {T6,T7,T8,T9,T10,T11,T12 병렬 가능, 단 generator(T7,T8,T11)는 T4 의존} → T13 → T14(전부 의존).
