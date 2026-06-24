# 외부 stub 응답 body 충실도 (REQ-012) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 발견된 외부 HTTP 호출 stub의 응답 body를 소비 코드 기대값(equals-family 리터럴·enum·에러 envelope) 기반으로 합성하고(CONTRACT), redirect-capable 호출은 그 값-충실 변형을 단언하는 생성 테스트로 환류한다.

**Architecture:** span 경로(`EgressStubComposer`)는 `stringLiteralsByDto`/enum/`ErrorContractDescriptor`로 happy body를 합성(CONTRACT). redirect 경로(`exploreResponseVariants`)는 변형 invoke 시 SUT status를 관측해 `discoveredBy="egress-assertion"` ExploredPath + CONTRACT `CapturedHttpCall`을 additive 환류한다. test-generator `Generator`는 그 마커를 생성에 포함하고, 변형은 별개 테스트 시나리오로 방출돼 WireMock shadow를 회피한다.

**Tech Stack:** Java 23, Gradle, JUnit5, AssertJ, Jackson, WireMock, Spoon(index), JaCoCo(pjacoco).

## Global Constraints

- 요구사항명세: `docs/superpowers/requirements/2026-06-24-egress-stub-body-fidelity-requirements.md` (REQ-F012-001~016; 017 🔵 deferred). 설계: `docs/superpowers/specs/2026-06-24-egress-stub-body-fidelity-design.md`.
- 결정성: body·envelope·변형 plan 합성은 시간/Random 금지, 동일 입력→동일 출력 (REQ-F012-011).
- surgical: 발견(REQ-001~011)·dedup(redirect 우선)·REQ-S015 형상-시드 등록·기존 `"response-variant"` 생성-제외 path는 행위 불변 (REQ-F012-012).
- provenance 의미: CAPTURED=실측 / CONTRACT=기대값·계약 / SYNTHESIZED=형상-시드. 레거시 JSON 누락→CAPTURED.
- 코드네임 위생: 사내 식별자 비노출, 일반화 표현만.
- 커밋 identity: `GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com`(committer 동일). 작업 브랜치 `feat-egress-stub-body-fidelity`. 커밋 전 `git rev-parse --abbrev-ref HEAD`로 브랜치 확인.
- 빠른 회귀: `./gradlew :graph-rag-builder:test :test-generator:test -PexcludeTags=integration`. 전체 SUT-boot E2E는 CI에 위임(다른 worktree SUT 누수 시 hang 가능).

---

## 외부 루프 (먼저 작성: E2E red) — Task 1

이중루프 원칙상 수용 E2E를 먼저 작성한다. 구현(Task 2~8) 전까지 RED(컴파일 실패 또는 단언 실패)가 정상이며, 약화·주석처리 금지.

### Task 1: 수용 E2E 3층 스캐폴드 (red)

**REQ-IDs:** REQ-F012-013, REQ-F012-014, REQ-F012-015, REQ-F012-016

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelityOtelE2E.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelitySpanOnlyE2E.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelitySleuthAbstainE2E.java`

**Interfaces:**
- Consumes(harness 템플릿): 기존 `OtelEgressDiscoveryE2E`(otel SUT-boot·`-Dsut.jar`/`-Dsut.src` 게이트·고유 PID teardown), `EgressStatusAgnosticStubE2E`(span-only: `EXTERNAL_INVENTORY_URL` 직접 host, recorder 미사용), `SleuthEgressDiscoveryE2E`(sleuth 기동), `Stage2AStringLiteralFuzzingE2E`(`EXTERNAL_INVENTORY_URL={{wiremock}}` redirect). 신규 E2E는 이 패턴을 그대로 따른다(자기 스코프 PID teardown, `@Tag("integration")`).
- Produces: 없음(수용 게이트).

- [ ] **Step 1: otel 단언 층 E2E 작성** — `EgressStubBodyFidelityOtelE2E`: order-service를 `EXTERNAL_INVENTORY_URL={{wiremock}}`(recorder redirect)·trace-mode otel로 빌드 실행 후, 생성 산출물/그래프에서 다음을 단언.
  - 생성 테스트 소스에 happy(201)·`region="EMBARGOED"`→422·`mode="BACKORDER"`→409에 대응하는 별개 테스트 메서드가 존재.
  - graph JSON `httpCalls`에 `responseProvenance=="CONTRACT"`이고 `responseBody`가 `"EMBARGOED"`/`"BACKORDER"`를 포함하는 항목 존재(placeholder `"sample-region"` 아님).
  - `@DisplayName("REQ-F012-013: otel redirect-capable 값-충실 변형 단언")`.
- [ ] **Step 2: span-only 층 E2E 작성** — `EgressStubBodyFidelitySpanOnlyE2E`: order-service를 `EXTERNAL_INVENTORY_URL`=직접 host stub(recorder/externalStubsDir 미사용)·trace-mode otel로 실행 후:
  - 발견된 호출의 생성 stub body가 `"EMBARGOED"`를 반영하고 graph `responseProvenance=="CONTRACT"`.
  - 그 호출 외부-응답 분기에 대한 SUT-status 단언 테스트 메서드는 없음(존재 부정 단언).
  - 빌더 로그/`externalLoudFails`에 `egress-branch-undriven` 노출.
  - `@DisplayName("REQ-F012-014: span-only CONTRACT body + 미구동 loud")`.
- [ ] **Step 3: sleuth abstain 층 E2E 작성** — `EgressStubBodyFidelitySleuthAbstainE2E`: order-web(sleuth) 실행 후, 외부 호출이 발견·기록되되 그 `httpCalls` 항목의 `responseProvenance != "CONTRACT"`(빈/형상 body 유지)임을 단언. `@DisplayName("REQ-F012-015: sleuth Void abstain — 거짓 CONTRACT 없음")`.
- [ ] **Step 4: 모든 종료 경로 teardown 보장** — 각 E2E는 기동한 SUT PID를 캡처해 try-finally/@AfterEach에서 그 PID만 종료, 스위트 종료 후 잔존 0 확인(REQ-F012-016). `pkill` 광범위·`docker system prune` 금지.
- [ ] **Step 5: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.EgressStubBodyFidelity*'`. Expected: FAIL/컴파일 에러(미구현). 약화 금지.
- [ ] **Step 6: 매트릭스 갱신 + commit** — 요구사항명세 매트릭스 013/014/015/016 🔴→🟡. Commit: `test(egress): REQ-012 수용 E2E 3층 스캐폴드(red) (REQ-F012-013/014/015/016)`.

---

## 내부 루프 (unit TDD) — Task 2~8

### Task 2: `Provenance.CONTRACT` 추가 + 후방호환

**REQ-IDs:** REQ-F012-004

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/CapturedHttpCall.java:18-21` (enum)
- Test: `shared-model/src/test/java/io/graphrag/model/CapturedHttpCallJsonRoundTripTest.java`

**Interfaces:**
- Produces: `CapturedHttpCall.Provenance.CONTRACT` enum 상수.

- [ ] **Step 1: 실패 테스트** — `CapturedHttpCallJsonRoundTripTest`에 추가:

```java
@Test
void contractProvenanceRoundTrips() throws Exception {
    var call = new CapturedHttpCall("h1", "p1", "GET", "/x", Map.of(), null,
            200, "{\"region\":\"EMBARGOED\"}", List.of(), false,
            CapturedHttpCall.Provenance.CONTRACT);
    String json = Json.mapper().writeValueAsString(call);
    var back = Json.mapper().readValue(json, CapturedHttpCall.class);
    assertThat(back.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
}

@Test
void legacyJsonWithoutProvenanceDefaultsToCaptured() throws Exception {
    String legacy = "{\"id\":\"h1\",\"pathId\":\"p1\",\"method\":\"GET\",\"urlPath\":\"/x\","
            + "\"responseStatus\":200,\"responseBody\":\"{}\"}";
    var back = Json.mapper().readValue(legacy, CapturedHttpCall.class);
    assertThat(back.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :shared-model:test --tests '*CapturedHttpCallJsonRoundTripTest'`. Expected: FAIL (CONTRACT 미존재 컴파일 에러).
- [ ] **Step 3: 구현** — `CapturedHttpCall.Provenance` enum에 `CONTRACT` 추가:

```java
public enum Provenance {
    CAPTURED,
    SYNTHESIZED,
    CONTRACT
}
```

Javadoc 1줄 갱신: `/** 출처: 실측(CAPTURED) / 형상-시드(SYNTHESIZED) / 기대값·계약 합성(CONTRACT). */`.

- [ ] **Step 4: green 확인** — Run: `./gradlew :shared-model:test --tests '*CapturedHttpCallJsonRoundTripTest'`. Expected: PASS.
- [ ] **Step 5: 매트릭스 004 🔴→🟢 + commit** — `feat(egress): CapturedHttpCall.Provenance.CONTRACT 추가 (REQ-F012-004)`.

---

### Task 3: `EgressStubComposer` 기대값 기반 body 합성 (String 리터럴·enum·폴백)

**REQ-IDs:** REQ-F012-001, REQ-F012-002, REQ-F012-010(폴백 silent), REQ-F012-011

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:2122` (compose 호출부)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressStubComposerContractTest.java` (신규)

**Interfaces:**
- Consumes: `ShapeJsonSynthesizer`, `ExternalCallSite.responseShape()`, runner 필드 `stringLiteralsByDto`(`Map<String,Map<String,List<String>>>`, dtoFqn→field→리터럴), `ErrorContractDescriptor`(Task 4에서 정의; 본 Task에선 null 허용 파라미터로만 추가).
- Produces: `EgressStubComposer.compose(EgressCall, List<ExternalCallSite>, ShapeJsonSynthesizer, Map<String,Map<String,List<String>>> stringLiteralsByDto, ErrorContractDescriptor errorContract)` → `Outcome`. (errorContract 사용은 Task 4.)

- [ ] **Step 1: 실패 테스트** — `EgressStubComposerContractTest`:

```java
// 픽스처: GET /inventory/stock, responseShape = BodyShape(dtoFqn="io.x.InventoryResponse",
//   fields=[region:String, mode:io.x.FulfillmentMode]), callSites=[그 site]
// stringLiteralsByDto = { "io.x.InventoryResponse": { "region": ["EMBARGOED"] } }
// enumConstants = { "io.x.FulfillmentMode": ["STANDARD","EXPRESS_ONLY","BACKORDER"] }

@Test
void literalSeeding_setsRegionToExtractedLiteral_andContractProvenance() {
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals, null);
    var body = Json.mapper().readTree(outcome.responseBody());
    assertThat(body.get("region").asText()).isEqualTo("EMBARGOED");
    assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
}

@Test
void enumOnlyStaysSynthesized_whenNoLiteralOrEnvelope() {
    // stringLiteralsByDto 비어있음, errorContract null → enum 첫 상수, SYNTHESIZED
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of(), null);
    var body = Json.mapper().readTree(outcome.responseBody());
    assertThat(body.get("mode").asText()).isEqualTo("STANDARD");        // 선언순 첫 상수
    assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
}

@Test
void fallbackSilent_noLoudFail_whenNoExpectedValueSource() {
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of(), null);
    assertThat(outcome.loudFail()).isEmpty();
}

@Test
void deterministic_sameInputSameOutput() {
    var a = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals, null);
    var b = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals, null);
    assertThat(a.responseBody()).isEqualTo(b.responseBody());
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposerContractTest'`. Expected: FAIL(시그니처/동작 미구현).
- [ ] **Step 3: 구현** — `EgressStubComposer.compose`에 `stringLiteralsByDto`·`errorContract` 파라미터를 추가하고, `ShapeJsonSynthesizer` 형상 합성 결과 위에 String 필드 리터럴을 덮어쓴다. dtoFqn은 `site.responseShape().get()`의 타입(BodyShape의 dtoFqn/javaType)을 키로 사용한다. 리터럴 적용이 1건 이상이면 `CONTRACT`, 아니면 기존 `SYNTHESIZED`(형상-시드). 형상 해소 불가/미매칭 loud-fail 경로는 기존대로 유지.

```java
static Outcome compose(EgressCall e, List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes,
                       Map<String, Map<String, List<String>>> stringLiteralsByDto,
                       ErrorContractDescriptor errorContract) {
    Optional<ExternalCallSite> site = CallSiteMatcher.match(e.method(), e.path(), callSites);
    if (site.isEmpty()) {
        return fail("unmatched-external-call", e.method() + " " + e.path());
    }
    Optional<BodyShape> shape = site.get().responseShape();
    if (shape.isEmpty()) {
        return fail("unwired-external-dep", e.method() + " " + e.path());
    }
    try {
        com.fasterxml.jackson.databind.JsonNode body = shapes.synthesizeBody(shape.get());
        boolean contract = applyExpectedStringLiterals(body, shape.get(), stringLiteralsByDto);
        // (errorContract envelope 적용은 Task 4에서 추가)
        return new Outcome(body.toString(),
                contract ? CapturedHttpCall.Provenance.CONTRACT : CapturedHttpCall.Provenance.SYNTHESIZED,
                Optional.empty());
    } catch (ShapeJsonSynthesizer.UnsupportedShapeException ex) {
        return fail("unsynthesizable-shape", site.get().pathLiteral());
    }
}

/** body의 String 필드를 dtoFqn→field→리터럴(첫 값)로 덮어쓴다. 1건이라도 적용하면 true. */
private static boolean applyExpectedStringLiterals(JsonNode body, BodyShape shape,
        Map<String, Map<String, List<String>>> stringLiteralsByDto) {
    if (!(body instanceof ObjectNode obj)) return false;
    Map<String, List<String>> byField = stringLiteralsByDto.getOrDefault(shape.dtoFqn(), Map.of());
    boolean applied = false;
    for (var entry : byField.entrySet()) {
        List<String> lits = entry.getValue();
        if (lits != null && !lits.isEmpty() && obj.has(entry.getKey())) {
            obj.put(entry.getKey(), lits.get(0));   // 결정적: 첫 리터럴
            applied = true;
        }
    }
    return applied;
}
```

> `BodyShape`는 `dtoFqn()`이 없다 — 타입 FQN 접근자는 `shape.javaType()`이다(확인됨: `BodyShape(String javaType, List<BodyField> fields, boolean collection)`). 위 `shape.dtoFqn()`은 `shape.javaType()`로 사용한다. `BodyField`는 `(String name, String javaType)`. `stringLiteralsByDto`의 dtoFqn 키는 `ResponseStringLiteralExtractor`가 responseShape의 javaType으로 버킷팅하므로 `shape.javaType()`와 일치한다.

- [ ] **Step 4: 호출부 갱신** — `EndpointExplorationRunner.captureHttpCalls`(L2122)의 `EgressStubComposer.compose(e, callSites, egressShapes)` → `EgressStubComposer.compose(e, callSites, egressShapes, stringLiteralsByDto, errorContract)`. `errorContract`는 Task 4 전까지 `null` 리터럴로 전달.
- [ ] **Step 5: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposerContractTest'`. Expected: PASS.
- [ ] **Step 6: 매트릭스 001/002/011 🔴→🟢, 010(폴백) 부분 + commit** — `feat(egress): EgressStubComposer 기대값 String 리터럴 합성·CONTRACT (REQ-F012-001/002/011)`.

---

### Task 4: 에러 envelope 합성기 + `ErrorContractDescriptor` 주입 배선

**REQ-IDs:** REQ-F012-003, REQ-F012-005

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ErrorContractDescriptor.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ErrorEnvelopeSynthesizer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (canonical 생성자 + 필드 `errorContract`)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (descriptor 파생·전달, L920 부근)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java` (envelope 적용)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ErrorEnvelopeSynthesizerTest.java` (신규), `graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressErrorContractWiringTest.java` (신규)

**Interfaces:**
- Produces: `record ErrorContractDescriptor(String semanticStatusField, String errorDetailField, String errorDetailContains)`; `ErrorEnvelopeSynthesizer.synthesize(ErrorContractDescriptor) -> JsonNode`; `ErrorContractDescriptor.fromClassifierConfig(ClassifierConfig) -> ErrorContractDescriptor|null`.

- [ ] **Step 1: envelope 합성기 실패 테스트** — `ErrorEnvelopeSynthesizerTest`:

```java
@Test
void synthesizesEnvelopeWithSentinelAndDetail() {
    var d = new ErrorContractDescriptor("errorCode", "errorDetail", "BizException");
    JsonNode env = new ErrorEnvelopeSynthesizer().synthesize(d);
    assertThat(env.get("errorCode").asText()).isEqualTo("ERROR");     // 고정 센티넬
    assertThat(env.get("errorDetail").asText()).isEqualTo("BizException");
}

@Test
void deterministic() {
    var d = new ErrorContractDescriptor("errorCode", "errorDetail", "BizException");
    var s = new ErrorEnvelopeSynthesizer();
    assertThat(s.synthesize(d).toString()).isEqualTo(s.synthesize(d).toString());
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ErrorEnvelopeSynthesizerTest'`. Expected: FAIL.
- [ ] **Step 3: 합성기 구현** — `ErrorContractDescriptor`(record) + `ErrorEnvelopeSynthesizer`:

```java
public final class ErrorEnvelopeSynthesizer {
    public com.fasterxml.jackson.databind.JsonNode synthesize(ErrorContractDescriptor d) {
        ObjectNode o = Json.mapper().createObjectNode();
        if (d.semanticStatusField() != null && !d.semanticStatusField().isBlank()) {
            o.put(d.semanticStatusField(), "ERROR");                 // 결정적 센티넬
        }
        if (d.errorDetailField() != null && !d.errorDetailField().isBlank()) {
            o.put(d.errorDetailField(), d.errorDetailContains() == null ? "" : d.errorDetailContains());
        }
        return o;
    }
}
```

`ErrorContractDescriptor.fromClassifierConfig`:

```java
public static ErrorContractDescriptor fromClassifierConfig(ClassifierConfig c) {
    if (c == null || c.errorWhenPresent() == null || c.errorWhenPresent().isEmpty()) {
        return null;   // errorWhenPresent 비어있으면 envelope 미적용(거짓 envelope 방지)
    }
    return new ErrorContractDescriptor(c.semanticStatusField(), c.errorDetailField(), c.errorDetailContains());
}
```

- [ ] **Step 4: 배선 실패 테스트** — `EgressErrorContractWiringTest`(synthetic descriptor):

```java
@Test
void nullDescriptor_whenErrorWhenPresentEmpty() {
    var cfg = ClassifierConfig.from(Map.of());   // errorWhenPresent 비어있음, semanticStatusField 기본 errorCode
    assertThat(ErrorContractDescriptor.fromClassifierConfig(cfg)).isNull();
}

@Test
void nonNullDescriptor_whenErrorWhenPresentSet() {
    var cfg = ClassifierConfig.from(Map.of(
        "--error-when-present", "errorCode",
        "--error-detail-field", "errorDetail",
        "--error-detail-contains", "BizException"));
    var d = ErrorContractDescriptor.fromClassifierConfig(cfg);
    assertThat(d).isNotNull();
    assertThat(d.errorDetailContains()).isEqualTo("BizException");
}
```

- [ ] **Step 5: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EgressErrorContractWiringTest'`. Expected: FAIL.
- [ ] **Step 6: runner·CLI 배선** — `EndpointExplorationRunner` canonical 생성자에 `ErrorContractDescriptor errorContract` 파라미터·필드 추가(맨 끝 인자; 기존 호환 생성자는 null 전달 오버로드 추가). `BuilderCli`(L920 부근, runner 생성 직전)에서 `ErrorContractDescriptor.fromClassifierConfig(config.classifierConfig())`를 만들어 전달(`toClassifier()`와 동일 config). `compose` 호출부(Task 3 Step 4)의 `null`을 이 필드로 교체.
- [ ] **Step 7: EgressStubComposer envelope 적용** — `compose`에서 errorContract != null이면, body의 envelope 필드(semanticStatusField/errorDetailField)가 responseShape에 있으면 그 값을 envelope 합성값으로 덮어쓰고 CONTRACT로 승격. (order-service처럼 envelope 미적용 SUT는 null이라 무변경.)
- [ ] **Step 8: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ErrorEnvelopeSynthesizerTest' --tests '*EgressErrorContractWiringTest'`. Expected: PASS.
- [ ] **Step 9: 매트릭스 003/005 🔴→🟢 + commit** — `feat(egress): 에러 envelope 합성기 + errorWhenPresent 게이트 주입 (REQ-F012-003/005)`.

---

### Task 5: 변형 SUT status 관측 + `egress-assertion` 단언 path 환류

**REQ-IDs:** REQ-F012-006, REQ-F012-007

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (`VariantInvoker`, `VariantOutcome`, `sendVariantAndDumpDelta`, `exploreResponseVariants`, `runResponseVariantLoops`)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ResponseVariantAssertionPathTest.java` (신규)
- Modify(blast-radius): `EnumVariantReExploreTest`, `EnumVariantNoneModeTest`, `StringLiteralVariantReExploreTest`, `StringLiteralVariantNoneModeTest` (VariantInvoker 스텁이 `VariantOutcome` 반환)

**Interfaces:**
- Produces: `record VariantOutcome(ExecutionDataStore coverage, int sutStatus)`; `VariantInvoker.invoke(JsonNode) -> VariantOutcome`; 새 arm 변형마다 `discoveredBy="egress-assertion"`·`expectedStatus=sutStatus`인 `ExploredPath` + provenance `CONTRACT`인 변형 `CapturedHttpCall`.

- [ ] **Step 1: 실패 테스트** — `ResponseVariantAssertionPathTest`(fake `VariantInvoker`로 status 주입):

```java
@Test
void emitsAssertionPath_withObservedStatus_andEgressAssertionMarker() {
    // fake invoker: region="EMBARGOED" 변형 invoke 시 sutStatus=422, 새 arm 개방
    var result = runner.exploreResponseVariantsForTest(plan, baselineBody, "GET",
            "/inventory/stock", synthesizer, /*isolated*/false, fakeInvoker, cumulative, appClasses);
    // 환류된 ExploredPath/CapturedHttpCall 검사
    ExploredPath assertion = findPath(result, "egress-assertion");
    assertThat(assertion.expectedStatus()).isEqualTo(422);
    assertThat(assertion.discoveredBy()).isEqualTo("egress-assertion");
}

@Test
void provenanceContract_onVariantCapturedHttpCall() {
    CapturedHttpCall variant = findVariantCall(result, "EMBARGOED");
    assertThat(variant.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
    assertThat(variant.responseBody()).contains("EMBARGOED");
}

@Test
void noAssertionPath_whenVariantOpensNoNewArm() {
    // fake invoker: 새 arm 미개방 → egress-assertion path 없음(기존 동작)
    assertThat(findPath(result, "egress-assertion")).isNull();
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest'`. Expected: FAIL.
- [ ] **Step 3: `VariantOutcome` 도입 + status 캡처** — `VariantInvoker.invoke` 반환형을 `ExecutionDataStore` → `VariantOutcome(ExecutionDataStore coverage, int sutStatus)`로 변경. `sendVariantAndDumpDelta`의 `http.send(...)` 결과를 받아 statusCode 캡처:

```java
public record VariantOutcome(ExecutionDataStore coverage, int sutStatus) {}
// interface
VariantOutcome invoke(JsonNode variantBody) throws Exception;
// sendVariantAndDumpDelta 내부
int status;
try {
    var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    status = resp.statusCode();
} finally {
    sqlScope.drain();
}
return new VariantOutcome(coverage.requestDelta(coverageTraceId), status);
```

`exploreResponseVariants`는 `invoker.invoke(body)`에서 `VariantOutcome`을 받아 `mergeAndDetectNewArm(cumulative, vo.coverage(), ...)`로 arm 판정하고, 새 arm이면 `KeptVariant`에 status를 함께 보존(`KeptVariant`에 `int sutStatus` 필드 추가).

- [ ] **Step 4: 단언 path 환류** — `runResponseVariantLoops`에서, 기존 cumulative `"response-variant"` path(불변) 외에, 각 `KeptVariant`마다 추가로:
  - 변형 body·provenance `CONTRACT`인 `CapturedHttpCall`(id `http-<endpoint>-egressassert-<label>`),
  - `discoveredBy="egress-assertion"`·`expectedStatus=kv.sutStatus()`·해당 변형 CapturedHttpCall id 참조·`triggerInput` sampleInput인 `ExploredPath`
  를 별도 리스트로 환류한다. (기존 `variantHttpCalls`/`variantPaths`의 SYNTHESIZED·`"response-variant"`는 그대로 둔다.)
- [ ] **Step 5: blast-radius 스텁 갱신** — `EnumVariantReExploreTest`·`EnumVariantNoneModeTest`·`StringLiteralVariantReExploreTest`·`StringLiteralVariantNoneModeTest`의 `VariantInvoker` 익명/fake 구현이 `VariantOutcome`을 반환하도록 수정(기존 status 무관 케이스는 `new VariantOutcome(delta, 200)`).
- [ ] **Step 6: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest' --tests '*EnumVariant*' --tests '*StringLiteralVariant*'`. Expected: PASS.
- [ ] **Step 7: 매트릭스 006/007 🔴→🟢 + commit** — `feat(egress): 변형 SUT status 관측 + egress-assertion 단언 path 환류 (REQ-F012-006/007)`.

---

### Task 6: `egress-branch-undriven` loud (span-only 미구동 가시화)

**REQ-IDs:** REQ-F012-010(loud part)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (변형 루프 종료 후 판정)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ResponseVariantAssertionPathTest.java` (케이스 추가)

**Interfaces:**
- Consumes: `callSites`, `stringLiteralsByDto`, `enumConstants`, `stubSynthesizer.isRegistered(method, path)`, `externalLoudFails`.

- [ ] **Step 1: 실패 테스트** — `ResponseVariantAssertionPathTest`에 추가:

```java
@Test
void undrivenLoud_whenSpanOnlyCallSiteHasCandidatesButNotRegistered() {
    // callSite에 String 리터럴 후보 존재 + stubSynthesizer.isRegistered==false(span-only)
    runner.flagUndrivenEgressBranchesForTest();   // 또는 runResponseVariantLoops 종료 경로
    assertThat(runner.externalLoudFailsForTest())
        .anyMatch(lf -> lf.reason().equals("egress-branch-undriven"));
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest'`. Expected: FAIL(새 케이스).
- [ ] **Step 3: 구현** — 변형 루프 종료 후, 각 `ExternalCallSite`에 대해 (responseShape 합성 가능) ∧ (변형 후보 존재: `stringLiteralsByDto` 또는 enum 비-첫상수) ∧ (`!stubSynthesizer.isRegistered(method, pathLiteral)`)이면 `externalLoudFails`에 `new LoudFail("egress-branch-undriven", method + " " + pathLiteral)` 추가(중복 방지: 기존 contains 체크).
- [ ] **Step 4: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest'`. Expected: PASS.
- [ ] **Step 5: 매트릭스 010 🔴→🟢 + commit** — `feat(egress): span-only 미구동 분기 egress-branch-undriven loud (REQ-F012-010)`.

---

### Task 7: `Generator`가 `egress-assertion` 생성 + `HttpMockComposer` CONTRACT body·시나리오 분리

**REQ-IDs:** REQ-F012-008, REQ-F012-009

**Files:**
- Modify: `test-generator/src/main/java/io/graphrag/generator/Generator.java:79-81` (제외 필터)
- Modify(필요 시): `test-generator/src/main/java/io/graphrag/generator/compose/HttpMockComposer.java`
- Test: `test-generator/src/test/java/io/graphrag/generator/GeneratorEgressAssertionTest.java` (신규), `test-generator/src/test/java/io/graphrag/generator/compose/HttpMockComposerContractBodyTest.java` (신규), `test-generator/src/test/java/io/graphrag/generator/compose/HttpMockComposerVariantScenarioTest.java` (신규)

**Interfaces:**
- Consumes: `ExploredPath.discoveredBy()=="egress-assertion"`, `CapturedHttpCall(Provenance.CONTRACT, responseBody)`.

- [ ] **Step 1: HttpMockComposer CONTRACT body 실패 테스트** — `HttpMockComposerContractBodyTest`:

```java
@Test
void emitsContractBodyVerbatim_notPlaceholder() {
    var call = new CapturedHttpCall("h1","p1","GET","/inventory/stock", Map.of(), null,
        200, "{\"region\":\"EMBARGOED\",\"mode\":\"BACKORDER\"}", List.of(), false,
        CapturedHttpCall.Provenance.CONTRACT);
    var mocks = new HttpMockComposer().compose(List.of(call));
    assertThat(mocks.block()).contains("EMBARGOED");
    assertThat(mocks.block()).doesNotContain("sample-region");
}
```

(기존 `stubBody`는 이미 responseBody를 방출하므로 통과 예상 — 회귀 가드. 실패 시 consumedFields 투영이 값을 깎지 않는지 점검.)

- [ ] **Step 2: Generator egress-assertion 실패 테스트** — `GeneratorEgressAssertionTest`:

```java
@Test
void egressAssertionPath_isGenerated_responseVariantStillExcluded() {
    // graph: 같은 endpoint에 "egress-assertion" path 1개 + "response-variant" path 1개
    var out = generator.generate(graph);
    assertThat(out.generatedTestCount()).isGreaterThanOrEqualTo(1);   // egress-assertion 포함
    // response-variant는 여전히 제외(테스트 메서드로 방출 안 됨)
}
```

- [ ] **Step 3: red 확인** — Run: `./gradlew :test-generator:test --tests '*GeneratorEgressAssertionTest' --tests '*HttpMockComposerContractBodyTest'`. Expected: FAIL.
- [ ] **Step 4: 구현** — `Generator.java:79-81` 제외 조건은 `"response-variant"`만 유지(이미 그러함; `"egress-assertion"`은 제외 목록에 없어 자동 포함). 만약 다른 곳에서 변형 path를 일괄 제외하면 `egress-assertion`만 허용하도록 보정. 변형 shadow 회피: `egress-assertion` path는 각자 별개 ExploredPath라 생성기가 path별 별개 테스트 메서드로 방출(기존 동작) → 한 테스트 scope에 단일 stub. `HttpMockComposer`는 변경 불필요(회귀 가드만).
- [ ] **Step 5: 시나리오 분리 테스트** — `HttpMockComposerVariantScenarioTest`: 한 테스트의 `compose(calls)`가 동일 (method,path)에 2개 stub을 등록하지 않음(각 path 단일 call)을 단언.
- [ ] **Step 6: green 확인** — Run: `./gradlew :test-generator:test --tests '*GeneratorEgressAssertionTest' --tests '*HttpMockComposer*Test'`. Expected: PASS.
- [ ] **Step 7: 매트릭스 008/009 🔴→🟢 + commit** — `feat(egress): Generator egress-assertion 생성 + CONTRACT body 방출 (REQ-F012-008/009)`.

---

### Task 8: E2E green + 전체 회귀

**REQ-IDs:** REQ-F012-012, REQ-F012-013, REQ-F012-014, REQ-F012-015, REQ-F012-016

**Files:**
- Modify: Task 1의 3개 E2E (필요 시 단언 미세조정)

- [ ] **Step 1: 빠른 회귀** — Run: `./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test -PexcludeTags=integration`. Expected: PASS(REQ-F012-012 — 기존 egress/REQ-S015/변형 스위트 green).
- [ ] **Step 2: E2E 실행(SUT-boot)** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.EgressStubBodyFidelity*'`(`-Dsut.jar`/`-Dsut.src` 게이트). Expected: 3층 PASS. 다른 worktree SUT 누수 없을 때만; hang 시 CI 위임.
- [ ] **Step 3: 자원 누수 검증** — E2E 종료 후 그 SUT PID 잔존 0 확인(REQ-F012-016). 잔존 시 green 주장 금지.
- [ ] **Step 4: 매트릭스 012~016 🟡→🟢 + commit** — `test(egress): REQ-012 E2E 3층 green + 회귀 (REQ-F012-012~016)`.

---

## Self-Review

**1. Spec coverage:**
- REQ-F012-001/002/011 → Task 3. 003/005 → Task 4. 004 → Task 2. 006/007 → Task 5. 008/009 → Task 7. 010(폴백 silent → Task 3, loud → Task 6). 012 → Task 8 Step 1. 013/014/015/016 → Task 1(red)+Task 8(green). 017 🔵 deferred(태스크 없음 — 정상).
- 모든 Must REQ가 ≥1 task에 매핑됨. 갭 없음.

**2. Placeholder scan:** 모든 step에 실제 코드/명령/기대 출력 포함. "적절히 처리" 류 없음. E2E(Task 1)는 기존 harness 클래스(`OtelEgressDiscoveryE2E` 등)를 템플릿으로 명시(플랜-내부 task 참조 아님, 실재 클래스).

**3. Type consistency:** `EgressStubComposer.compose`(5-arg, Task 3)·`ErrorContractDescriptor`(Task 4)·`VariantOutcome`/`VariantInvoker.invoke`(Task 5)·`discoveredBy="egress-assertion"`(Task 5↔7)·`Provenance.CONTRACT`(Task 2, 전역) 명칭 일관. `ExploredPath` 14-arg 호환 생성자(shared-model 확인)·`CapturedHttpCall` 11-arg(provenance) 생성자 사용.

**확정:** `BodyShape`는 `javaType()`(타입 FQN)·`fields()`(`BodyField(name, javaType)`)·`collection()`만 가짐 — dtoFqn 키는 `shape.javaType()` 사용(Task 3 반영 완료).

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-24-egress-stub-body-fidelity.md`.
권장: **Subagent-Driven** (superpowers:subagent-driven-development) — task마다 fresh subagent + 2-stage(spec/quality) 리뷰, task 간 검토. 절대경로 worktree·커밋 전 브랜치 확인 의무화.
