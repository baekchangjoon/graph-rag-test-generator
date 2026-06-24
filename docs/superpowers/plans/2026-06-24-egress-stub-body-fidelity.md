# 외부 stub 응답 body 충실도 (REQ-012) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 발견된 외부 HTTP 호출 stub의 응답 body를 소비 코드 기대값(equals-family 리터럴·enum·에러 envelope) 기반으로 합성하고(CONTRACT), redirect-capable 호출은 그 값-충실 변형을 단언하는 생성 테스트로 환류한다.

**Architecture:** span 경로(`EgressStubComposer`)는 `stringLiteralsByDto`/enum으로 happy body를 합성(CONTRACT). redirect 경로(`exploreResponseVariants`)는 변형 invoke 시 SUT status를 관측해 `discoveredBy="egress-assertion"` ExploredPath + CONTRACT `CapturedHttpCall`을 `variantPaths`에 additive 환류한다. 에러 envelope은 별도 합성기로 **에러 변형 body로만** 쓰며 happy body는 오염하지 않는다. test-generator `Generator`는 `egress-assertion` 마커를 생성에 포함하고, 변형은 별개 테스트 시나리오로 방출돼 WireMock shadow를 회피한다.

**Tech Stack:** Java 23, Gradle, JUnit5, AssertJ, Jackson, WireMock, Spoon(index), JaCoCo(pjacoco).

## Global Constraints

- 요구사항명세: `docs/superpowers/requirements/2026-06-24-egress-stub-body-fidelity-requirements.md` (REQ-F012-001~016; 017 🔵 deferred). 설계: `docs/superpowers/specs/2026-06-24-egress-stub-body-fidelity-design.md`.
- 결정성(REQ-F012-011): body·envelope·변형 plan 합성은 시간/Random 금지, 동일 입력→동일 출력.
- surgical(REQ-F012-012): 발견(REQ-001~011)·dedup(redirect 우선)·REQ-S015 형상-시드 등록·기존 `"response-variant"` 생성-제외 path는 행위 불변.
- **envelope는 happy body를 오염하지 않는다**: happy span compose는 String 리터럴·enum 첫 상수만. envelope은 에러 변형 body 전용.
- provenance: CAPTURED=실측 / CONTRACT=기대값·계약 / SYNTHESIZED=형상-시드. 레거시 JSON 누락→CAPTURED.
- 코드네임 위생: 사내 식별자 비노출.
- 커밋 identity: `GIT_AUTHOR_NAME=baekchangjoon GIT_AUTHOR_EMAIL=changjoon.baek@icloud.com`(committer 동일). 작업 브랜치 `feat-egress-stub-body-fidelity`. 커밋 전 `git rev-parse --abbrev-ref HEAD` 확인.
- 빠른 회귀: `./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test -PexcludeTags=integration`. SUT-boot E2E는 CI 위임 가능(다른 worktree SUT 누수 시 hang).
- **확정 시그니처(grounding 완료)**: `BodyShape(String javaType, List<BodyField> fields, boolean collection)`, `BodyField(String name, String javaType)` — dtoFqn 키는 `shape.javaType()`. `CapturedHttpCall` 11-arg(+`Provenance`) 생성자. `ExploredPath` 14-arg 호환 생성자(`id,endpointId,sampleInput,expectedStatus,sampleResponse,capturedSqlIds,capturedHttpCallIds,branchesTaken,discoveredBy,constraints,validationWarnings,requiredSeedIds,capturedEventEmitIds,responseHeaders`). `GenerationResult(List<GeneratedFile> files, List<String> warnings, ParallelSafetyReport parallelSafety)` — `generatedTestCount()` 없음. `ClassifierConfig(errorWhenPresent, semanticStatusField, errorDetailField, errorDetailContains)`, `from()`이 `semanticStatusField` 기본 `"errorCode"` 항상 세팅. `exploreResponseVariants`는 **static**, `VariantExploreResult` 반환. `externalLoudFails`는 runner private `List<LoudFail>`; `EndpointResult.externalLoudFails()`로 노출, `BuilderCli`가 `exploration-report.json`의 `unsupportedShapes`(reason/target)로 직렬화. `Generator` 제외 목록(`Generator.java:79-81`)은 `negative-auth`/`negative-validation`/`response-variant`.

---

## 외부 루프 (먼저 작성: E2E red) — Task 1

이중루프상 수용 E2E를 먼저 작성한다. 구현(Task 2~8) 전까지 RED가 정상이며 약화 금지.
**핵심**: 이 E2E들은 `BuilderCli.build`(→ graph)에 더해 **`Generator`를 실행해 생성 테스트 소스를 만들고 그 소스에 단언**한다(기존 builder-only E2E와 다름).

### Task 1: 수용 E2E 3층 스캐폴드 (red)

**REQ-IDs:** REQ-F012-013, REQ-F012-014, REQ-F012-015, REQ-F012-016

**Files:**
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelityOtelE2E.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelitySpanOnlyE2E.java`
- Create: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/EgressStubBodyFidelitySleuthAbstainE2E.java`

**Interfaces:**
- Consumes(harness 템플릿, 정확히 지정):
  - **빌드 단계**: `Stage2AStringLiteralFuzzingE2E`의 `build()` 헬퍼 패턴 — `BuilderCli` 호출, `@EnabledIfSystemProperty`로 `sut.jar`·`sut.src` 둘 다 게이트, otel redirect 층은 `--trace-mode otel` + 환경 `EXTERNAL_INVENTORY_URL={{wiremock}}`(recorder redirect → `stubSynthesizer.isRegistered==true` → 변형 루프 구동). span-only 층은 `EgressStatusAgnosticStubE2E` 패턴 — `EXTERNAL_INVENTORY_URL`=직접 host stub URL(recorder 미사용)·`--trace-mode otel`.
  - **생성 단계**: `GeneratorVariantExclusionTest`/`GeneratorTest` 패턴 — build 산출 graph 디렉터리를 입력으로 `Generator`를 실행해 `GenerationResult.files()`를 얻고, 생성 소스 텍스트에 단언.
  - **loud 단언**: `BuilderIntegrationTest`(L378 부근) 패턴 — `Files.readString(buildOut.resolve("exploration-report.json"))` 파싱 후 `unsupportedShapes[].reason`에서 확인.
- Produces: 없음(수용 게이트).

- [ ] **Step 1: otel 단언 층** — `EgressStubBodyFidelityOtelE2E`(`@EnabledIfSystemProperty` sut.jar+sut.src): order-service를 `EXTERNAL_INVENTORY_URL={{wiremock}}`·`--trace-mode otel`로 build → graph. 이어 `Generator` 실행 → `GenerationResult.files()`. 단언:
  - 생성 소스 합본에 happy(201)·`region="EMBARGOED"`(422)·`mode="BACKORDER"`(409)에 대응하는 별개 테스트 메서드/단언이 존재(상태코드·stub body 문자열로 검사).
  - graph JSON `httpCalls`에 `responseProvenance=="CONTRACT"` & `responseBody`에 `"EMBARGOED"`/`"BACKORDER"` 포함(placeholder `"sample-region"` 아님).
  - `@DisplayName("REQ-F012-013: otel redirect-capable 값-충실 변형 단언")`.
- [ ] **Step 2: span-only 층** — `EgressStubBodyFidelitySpanOnlyE2E`: order-service를 `EXTERNAL_INVENTORY_URL`=직접 host stub·`--trace-mode otel`로 build → graph → `Generator`. 단언:
  - 발견 호출의 생성 stub body가 `"EMBARGOED"` 반영, graph `responseProvenance=="CONTRACT"`.
  - 그 외부-응답 분기에 대한 SUT-status 단언 테스트 메서드 부재(소스에 422/409 외부-의존 단언 없음).
  - `exploration-report.json`의 `unsupportedShapes[].reason`에 `egress-branch-undriven` 존재.
  - `@DisplayName("REQ-F012-014: span-only CONTRACT body + 미구동 loud")`.
- [ ] **Step 3: sleuth abstain 층** — `EgressStubBodyFidelitySleuthAbstainE2E`: order-web(sleuth) build → graph. 단언: 외부 호출 `httpCalls` 항목의 `responseProvenance != "CONTRACT"`(빈/형상 body). `@DisplayName("REQ-F012-015: sleuth Void abstain — 거짓 CONTRACT 없음")`.
- [ ] **Step 4: 모든 종료 경로 teardown** — 각 E2E는 기동 SUT PID 캡처 → try-finally/@AfterEach에서 그 PID만 종료, 스위트 종료 후 잔존 0(REQ-F012-016). `pkill` 광범위·`docker system prune` 금지.
- [ ] **Step 5: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.EgressStubBodyFidelity*'`. Expected: FAIL/컴파일 에러. 약화 금지.
- [ ] **Step 6: 매트릭스 013/014/015/016 🔴→🟡 + commit** — `test(egress): REQ-012 수용 E2E 3층 스캐폴드(red) (REQ-F012-013/014/015/016)`.

---

## 내부 루프 (unit TDD) — Task 2~8

### Task 2: `Provenance.CONTRACT` 추가 + 후방호환

**REQ-IDs:** REQ-F012-004

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/CapturedHttpCall.java:18-21` (enum)
- Test: `shared-model/src/test/java/io/graphrag/model/CapturedHttpCallJsonRoundTripTest.java`

**Interfaces:**
- Produces: `CapturedHttpCall.Provenance.CONTRACT`.

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

- [ ] **Step 2: red 확인** — Run: `./gradlew :shared-model:test --tests '*CapturedHttpCallJsonRoundTripTest'`. Expected: FAIL(CONTRACT 미존재).
- [ ] **Step 3: 구현** — enum에 `CONTRACT` 추가:

```java
public enum Provenance {
    CAPTURED,
    SYNTHESIZED,
    CONTRACT
}
```

Javadoc: `/** 출처: 실측(CAPTURED) / 형상-시드(SYNTHESIZED) / 기대값·계약 합성(CONTRACT). */`.

- [ ] **Step 4: green 확인** — Run: `./gradlew :shared-model:test --tests '*CapturedHttpCallJsonRoundTripTest'`. Expected: PASS.
- [ ] **Step 5: 매트릭스 004 🔴→🟢 + commit** — `feat(egress): CapturedHttpCall.Provenance.CONTRACT 추가 (REQ-F012-004)`.

---

### Task 3: `EgressStubComposer` happy body 기대값 합성 (String 리터럴·enum·폴백)

**REQ-IDs:** REQ-F012-001, REQ-F012-002, REQ-F012-010(폴백 silent), REQ-F012-011

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EgressStubComposer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java:2122` (compose 호출부)
- Modify: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressStubComposerTest.java` (기존 3-arg 호출부 → 신규 시그니처)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EgressStubComposerContractTest.java` (신규)

**Interfaces:**
- Consumes: `ShapeJsonSynthesizer`, `ExternalCallSite.responseShape()`, runner 필드 `stringLiteralsByDto`(`Map<String,Map<String,List<String>>>`, javaType→field→리터럴).
- Produces: `EgressStubComposer.compose(EgressCall, List<ExternalCallSite>, ShapeJsonSynthesizer, Map<String,Map<String,List<String>>> stringLiteralsByDto)` → `Outcome`. **envelope 파라미터 없음**(envelope은 Task 4의 변형 경로 전용, happy compose 미관여).

- [ ] **Step 1: 실패 테스트** — `EgressStubComposerContractTest`(픽스처: GET /inventory/stock, responseShape javaType=`io.x.InventoryResponse`, fields=[region:String, mode:io.x.FulfillmentMode]; `stringLiteralsByDto={"io.x.InventoryResponse":{"region":["EMBARGOED"]}}`; enumConstants={"io.x.FulfillmentMode":["STANDARD","EXPRESS_ONLY","BACKORDER"]}):

```java
@Test
void literalSeeding_setsRegionToExtractedLiteral_andContractProvenance() throws Exception {
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
    var body = Json.mapper().readTree(outcome.responseBody());
    assertThat(body.get("region").asText()).isEqualTo("EMBARGOED");
    assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
}

@Test
void enumOnlyStaysSynthesized_whenNoLiteral() throws Exception {
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of());
    var body = Json.mapper().readTree(outcome.responseBody());
    assertThat(body.get("mode").asText()).isEqualTo("STANDARD");   // 선언순 첫 상수
    assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
}

@Test
void fallbackSilent_noLoudFail_whenNoLiteral() {
    var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of());
    assertThat(outcome.loudFail()).isEmpty();
}

@Test
void deterministic_sameInputSameOutput() {
    var a = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
    var b = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
    assertThat(a.responseBody()).isEqualTo(b.responseBody());
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposerContractTest'`. Expected: FAIL.
- [ ] **Step 3: 구현** — `compose`에 `stringLiteralsByDto` 추가, 형상 합성 위에 String 리터럴 덮어쓰기:

```java
static Outcome compose(EgressCall e, List<ExternalCallSite> callSites, ShapeJsonSynthesizer shapes,
                       Map<String, Map<String, List<String>>> stringLiteralsByDto) {
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
        boolean contract = applyExpectedStringLiterals(body, shape.get(), stringLiteralsByDto);
        return new Outcome(body.toString(),
                contract ? CapturedHttpCall.Provenance.CONTRACT : CapturedHttpCall.Provenance.SYNTHESIZED,
                Optional.empty());
    } catch (ShapeJsonSynthesizer.UnsupportedShapeException ex) {
        return fail("unsynthesizable-shape", site.get().pathLiteral());
    }
}

/** body의 String 필드를 javaType→field→리터럴(첫 값)로 덮어쓴다. 1건이라도 적용하면 true. */
private static boolean applyExpectedStringLiterals(JsonNode body, BodyShape shape,
        Map<String, Map<String, List<String>>> stringLiteralsByDto) {
    if (!(body instanceof ObjectNode obj)) return false;
    Map<String, List<String>> byField = stringLiteralsByDto.getOrDefault(shape.javaType(), Map.of());
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

- [ ] **Step 4: 호출부 + 기존 테스트 갱신** — `EndpointExplorationRunner.captureHttpCalls`(L2122) `compose(e, callSites, egressShapes)` → `compose(e, callSites, egressShapes, stringLiteralsByDto)`. 기존 `EgressStubComposerTest`의 3-arg `compose` 호출(4곳) → 4-arg(`Map.of()` 전달)로 수정.
- [ ] **Step 5: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EgressStubComposer*'`. Expected: PASS(신규+기존).
- [ ] **Step 6: 매트릭스 001/002/011 🔴→🟢 + commit** — `feat(egress): EgressStubComposer happy body String 리터럴·CONTRACT (REQ-F012-001/002/011)`.

---

### Task 4: 에러 envelope 합성기 + `ErrorContractDescriptor` 주입 (변형 전용, happy 미관여)

**REQ-IDs:** REQ-F012-003, REQ-F012-005

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ErrorContractDescriptor.java`
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ErrorEnvelopeSynthesizer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (canonical 생성자 + 필드 `errorContract`)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` (descriptor 파생·전달, 생성 site)
- Test: `ErrorEnvelopeSynthesizerTest`, `EgressErrorContractWiringTest` (둘 다 신규, synthetic)

**Interfaces:**
- Produces: `record ErrorContractDescriptor(List<String> errorWhenPresent, String semanticStatusField, String errorDetailField, String errorDetailContains)`; `ErrorEnvelopeSynthesizer.synthesize(ErrorContractDescriptor) -> JsonNode`; `ErrorContractDescriptor.fromClassifierConfig(ClassifierConfig) -> ErrorContractDescriptor|null`.
- **범위 주의**: egress+envelope 겸비 sample SUT 부재 → synthetic descriptor unit/integration로만 검증(E2E 없음). envelope body는 **에러 변형 후보**로만 쓰며(REQ-F012-006 변형 경로에서 소비) happy compose(Task 3) 미관여.

- [ ] **Step 1: 합성기 실패 테스트** — `ErrorEnvelopeSynthesizerTest`:

```java
@Test
void synthesizesEnvelope_triggerFields_sentinel_detail() {
    var d = new ErrorContractDescriptor(List.of("errorCode"), "errorCode", "errorDetail", "BizException");
    JsonNode env = new ErrorEnvelopeSynthesizer().synthesize(d);
    assertThat(env.get("errorCode").asText()).isEqualTo("ERROR");      // trigger+status 센티넬(비어있지 않음)
    assertThat(env.get("errorDetail").asText()).isEqualTo("BizException");
}

@Test
void deterministic() {
    var d = new ErrorContractDescriptor(List.of("errorCode"), "errorCode", "errorDetail", "BizException");
    var s = new ErrorEnvelopeSynthesizer();
    assertThat(s.synthesize(d).toString()).isEqualTo(s.synthesize(d).toString());
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ErrorEnvelopeSynthesizerTest'`. Expected: FAIL.
- [ ] **Step 3: 합성기 구현** — `ErrorEnvelopeClassifier`는 `errorWhenPresent` 필드가 **존재+비어있지 않음**으로 에러를 판정하므로, 그 트리거 필드를 비어있지 않은 값으로 채워야 한다:

```java
public final class ErrorEnvelopeSynthesizer {
    public JsonNode synthesize(ErrorContractDescriptor d) {
        ObjectNode o = Json.mapper().createObjectNode();
        for (String trigger : d.errorWhenPresent()) {            // 분류기 트리거 필드(비어있지 않게)
            if (trigger != null && !trigger.isBlank()) o.put(trigger, "ERROR");
        }
        if (d.semanticStatusField() != null && !d.semanticStatusField().isBlank()) {
            o.put(d.semanticStatusField(), "ERROR");             // 결정적 센티넬
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
        return null;   // errorWhenPresent 비어있으면 envelope 미적용(거짓 envelope 방지; from()의 기본 semanticStatusField와 무관)
    }
    return new ErrorContractDescriptor(c.errorWhenPresent(), c.semanticStatusField(),
            c.errorDetailField(), c.errorDetailContains());
}
```

- [ ] **Step 4: 배선 실패 테스트** — `EgressErrorContractWiringTest`(synthetic):

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
- [ ] **Step 6: runner·CLI 배선(blast-radius 명시)** — `EndpointExplorationRunner` canonical 생성자(현 21-arg, `BuilderCli` 생성 site에서 호출) 맨 끝에 `ErrorContractDescriptor errorContract` 파라미터·필드 추가. 기존 호환 생성자(`callSites` 받는 생성자 등 2개)는 본문에서 canonical로 위임할 때 `null` 전달. 직접 canonical 생성자를 호출하는 모든 사이트 갱신: `BuilderCli`의 runner 생성 site(`ErrorContractDescriptor.fromClassifierConfig(config.classifierConfig())` 전달) + 테스트가 canonical을 직접 부르면 그 사이트도. (대부분 테스트는 호환 생성자를 쓰므로 null 위임으로 무변경.)
- [ ] **Step 7: 중간 회귀** — Run: `./gradlew :graph-rag-builder:test -PexcludeTags=integration`. Expected: PASS(생성자 blast-radius 컴파일·회귀 green). 실패 시 누락 호출부 보정.
- [ ] **Step 8: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ErrorEnvelopeSynthesizerTest' --tests '*EgressErrorContractWiringTest'`. Expected: PASS.
- [ ] **Step 9: 매트릭스 003/005 🔴→🟢 + commit** — `feat(egress): 에러 envelope 합성기(트리거 필드) + errorWhenPresent 게이트 주입 (REQ-F012-003/005)`.

> envelope body의 실제 변형-소비(redirect 경로에 에러 변형으로 주입)는 sample SUT 부재로 본 작업에선 unit/synthetic 검증에 한정한다. happy compose는 envelope을 적용하지 않는다.

---

### Task 5: 변형 SUT status 관측 + `egress-assertion` 단언 path 환류

**REQ-IDs:** REQ-F012-006, REQ-F012-007

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (`VariantInvoker`, `VariantOutcome`, `KeptVariant`, `sendVariantAndDumpDelta`, `realVariantInvoker` anon, `exploreResponseVariants`, `runResponseVariantLoops`, 신규 pure helper)
- Test: `ResponseVariantAssertionPathTest` (신규)
- Modify(blast-radius): `EnumVariantReExploreTest`, `EnumVariantNoneModeTest`, `StringLiteralVariantReExploreTest`, `StringLiteralVariantNoneModeTest`

**Interfaces:**
- Produces: `record VariantOutcome(ExecutionDataStore coverage, int sutStatus)`; `VariantInvoker.invoke(JsonNode) -> VariantOutcome`; `KeptVariant(String label, JsonNode variantBody, int sutStatus, List<BranchRef> branches)`; pure helper `static List<ExploredPath> buildEgressAssertionPaths(String endpointId, JsonNode triggerInput, String method, String pathLiteral, List<KeptVariant> kept, List<CapturedHttpCall> outVariantCalls)` — 각 KeptVariant마다 CONTRACT `CapturedHttpCall`(id `http-<endpoint>-egressassert-<label>`)을 `outVariantCalls`에 add하고, `discoveredBy="egress-assertion"`·`expectedStatus=sutStatus`·`branchesTaken=branches`·그 call id 참조인 `ExploredPath`를 반환.

- [ ] **Step 1: pure helper 실패 테스트** — `ResponseVariantAssertionPathTest`(static helper 직접 호출, runner 인스턴스/유령 메서드 없음):

```java
@Test
void buildsAssertionPath_withObservedStatus_egressAssertionMarker_andContractCall() {
    var kept = List.of(new EndpointExplorationRunner.KeptVariant(
            "region=EMBARGOED", Json.mapper().readTree("{\"region\":\"EMBARGOED\"}"), 422,
            List.of(new BranchRef("io.x.OrderController", 53))));
    var outCalls = new ArrayList<CapturedHttpCall>();
    var paths = EndpointExplorationRunner.buildEgressAssertionPaths(
            "ep1", triggerInput, "GET", "/inventory/stock", kept, outCalls);

    assertThat(paths).hasSize(1);
    assertThat(paths.get(0).discoveredBy()).isEqualTo("egress-assertion");
    assertThat(paths.get(0).expectedStatus()).isEqualTo(422);
    assertThat(outCalls).hasSize(1);
    assertThat(outCalls.get(0).responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
    assertThat(outCalls.get(0).responseBody()).contains("EMBARGOED");
    assertThat(paths.get(0).capturedHttpCallIds()).containsExactly(outCalls.get(0).id());
}

@Test
void emptyKept_yieldsNoPaths() {
    var outCalls = new ArrayList<CapturedHttpCall>();
    assertThat(EndpointExplorationRunner.buildEgressAssertionPaths(
            "ep1", triggerInput, "GET", "/x", List.of(), outCalls)).isEmpty();
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest'`. Expected: FAIL.
- [ ] **Step 3: `VariantOutcome` + status 캡처 + KeptVariant 확장** — `VariantInvoker.invoke` 반환 `ExecutionDataStore` → `VariantOutcome(ExecutionDataStore coverage, int sutStatus)`. `sendVariantAndDumpDelta`가 `http.send` 결과 statusCode 캡처:

```java
public record VariantOutcome(ExecutionDataStore coverage, int sutStatus) {}
// interface: VariantOutcome invoke(JsonNode variantBody) throws Exception;
// sendVariantAndDumpDelta 내부:
int status;
try {
    var resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    status = resp.statusCode();
} finally {
    sqlScope.drain();
}
return new VariantOutcome(coverage.requestDelta(coverageTraceId), status);
```

`realVariantInvoker`의 anon `VariantInvoker.invoke`도 반환형을 `VariantOutcome`으로 바꾸고 `return sendVariantAndDumpDelta(...)`(이미 VariantOutcome 반환). `KeptVariant`에 `int sutStatus`·`List<BranchRef> branches` 추가. `exploreResponseVariants`에서 새 arm 변형 보존 시 `vo.sutStatus()`와 `analyzer.analyze(vo.coverage()).covered()`(per-variant delta 분기)를 KeptVariant에 채운다.

- [ ] **Step 4: helper + 환류 구현** — `buildEgressAssertionPaths` 구현. `runResponseVariantLoops`에서 cumulative `"response-variant"` path(불변) 외에, `buildEgressAssertionPaths(...)` 결과를 **기존 `variantPaths`/`variantHttpCalls`에 add**(이들은 `run()` 종료 시 `finalPaths`/`finalHttpCalls`로 병합되어 graph 환류). 즉 egress-assertion path도 동일 병합 경로를 탄다.
- [ ] **Step 5: blast-radius 스텁 갱신** — `EnumVariant*`/`StringLiteralVariant*` 4개 테스트의 `VariantInvoker` 구현이 `VariantOutcome` 반환(status 무관 케이스 `new VariantOutcome(delta, 200)`).
- [ ] **Step 6: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseVariantAssertionPathTest' --tests '*EnumVariant*' --tests '*StringLiteralVariant*'`. Expected: PASS.
- [ ] **Step 7: 매트릭스 006/007 🔴→🟢 + commit** — `feat(egress): 변형 SUT status 관측 + egress-assertion 단언 path 환류 (REQ-F012-006/007)`.

---

### Task 6: `egress-branch-undriven` loud (span-only 미구동 가시화)

**REQ-IDs:** REQ-F012-010(loud part)

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (pure helper + runResponseVariantLoops 종료 후 호출)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/UndrivenEgressBranchTest.java` (신규)

**Interfaces:**
- Produces: pure helper `static List<LoudFail> undrivenEgressBranches(List<ExternalCallSite> callSites, Map<String,Map<String,List<String>>> stringLiteralsByDto, Map<String,List<String>> enumConstants, java.util.function.BiPredicate<String,String> isRegistered)` — responseShape 보유 ∧ 변형 후보 존재(리터럴 또는 enum 비-첫상수) ∧ `!isRegistered(method, pathLiteral)`인 site마다 `LoudFail("egress-branch-undriven", method+" "+pathLiteral)`.

- [ ] **Step 1: 실패 테스트** — `UndrivenEgressBranchTest`(pure helper 직접):

```java
@Test
void flagsUndriven_whenCandidatesExistButNotRegistered() {
    var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(shapeWithStringRegion));
    var literals = Map.of("io.x.InventoryResponse", Map.of("region", List.of("EMBARGOED")));
    var loud = EndpointExplorationRunner.undrivenEgressBranches(
            List.of(site), literals, Map.of(), (m, p) -> false);   // 미등록 = span-only
    assertThat(loud).anyMatch(lf -> lf.reason().equals("egress-branch-undriven")
            && lf.target().equals("GET /inventory/stock"));
}

@Test
void noFlag_whenRegistered() {
    var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(shapeWithStringRegion));
    var literals = Map.of("io.x.InventoryResponse", Map.of("region", List.of("EMBARGOED")));
    assertThat(EndpointExplorationRunner.undrivenEgressBranches(
            List.of(site), literals, Map.of(), (m, p) -> true)).isEmpty();
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*UndrivenEgressBranchTest'`. Expected: FAIL.
- [ ] **Step 3: 구현** — `undrivenEgressBranches` 구현(중복 target 제거). `runResponseVariantLoops` 종료부에서 `undrivenEgressBranches(callSites, stringLiteralsByDto, enumConstants, stubSynthesizer::isRegistered)` 호출 결과를 `externalLoudFails`에 추가(기존 contains 중복 방지).
- [ ] **Step 4: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*UndrivenEgressBranchTest'`. Expected: PASS.
- [ ] **Step 5: 매트릭스 010 🔴→🟢 + commit** — `feat(egress): span-only 미구동 egress-branch-undriven loud (REQ-F012-010)`.

---

### Task 7: `Generator`가 `egress-assertion` 생성 + `HttpMockComposer` CONTRACT body

**REQ-IDs:** REQ-F012-008, REQ-F012-009

**Files:**
- Modify(필요 시): `test-generator/src/main/java/io/graphrag/generator/Generator.java:79-81`
- Test: `GeneratorEgressAssertionTest`, `HttpMockComposerContractBodyTest` (둘 다 신규)

**Interfaces:**
- Consumes: `ExploredPath.discoveredBy()=="egress-assertion"`, `CapturedHttpCall(Provenance.CONTRACT, responseBody)`, `GenerationResult.files()`.

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

(기존 `stubBody`가 responseBody를 방출하므로 통과 예상 — consumedFields 빈 리스트면 전체 body 방출. 회귀 가드.)

- [ ] **Step 2: Generator egress-assertion 실패 테스트** — `GeneratorEgressAssertionTest`(`GeneratorVariantExclusionTest` 픽스처 패턴, `result.files()` 검사):

```java
// 패턴: GeneratorVariantExclusionTest — in-memory GraphRagClient client(List<ExploredPath>),
//   GenerationRequest(endpointId, pathId(null), testClass, pkg, AuthMode), generate(req).
@Test
void egressAssertionPath_isGenerated_responseVariantStillExcluded() {
    // client: 한 endpoint에 discoveredBy="egress-assertion" path 1개 + "response-variant" path 1개
    GenerationRequest req = new GenerationRequest(
            "get-inventory-stock", null, "InventoryEgressTest", "io.x", AuthMode.REAL);
    GenerationResult result =
            new Generator(client(List.of(egressAssertionPath(), responseVariantPath()))).generate(req);
    String allSource = result.files().stream()
            .filter(f -> f.relativePath().endsWith(".java"))
            .map(GeneratedFile::content).collect(Collectors.joining("\n"));
    assertThat(result.files()).isNotEmpty();
    assertThat(allSource).contains("EMBARGOED");                 // egress-assertion 변형이 생성됨
    // response-variant 전용 메서드("responsevar_...")는 소스에 없음(여전히 제외)
    assertThat(allSource).doesNotContain("responsevar");
}
```

- [ ] **Step 3: red 확인** — Run: `./gradlew :test-generator:test --tests '*GeneratorEgressAssertionTest' --tests '*HttpMockComposerContractBodyTest'`. Expected: FAIL.
- [ ] **Step 4: 구현** — `Generator.java:79-81` 제외 목록은 `negative-auth`/`negative-validation`/`response-variant`만 — `egress-assertion`은 목록에 없어 **이미 생성에 포함**된다. 만약 다른 제외/필터가 변형 path를 막으면 `egress-assertion`만 허용하도록 보정. shadow 회피: egress-assertion path는 각자 별개 ExploredPath → 생성기가 path별 별개 테스트 메서드로 방출(기존 동작) → 한 테스트 scope 단일 stub. `HttpMockComposer` 변경 불필요(회귀 가드만).
- [ ] **Step 5: green 확인** — Run: `./gradlew :test-generator:test --tests '*GeneratorEgressAssertionTest' --tests '*HttpMockComposerContractBodyTest'`. Expected: PASS.
- [ ] **Step 6: 매트릭스 008/009 🔴→🟢 + commit** — `feat(egress): Generator egress-assertion 생성 + CONTRACT body 방출 (REQ-F012-008/009)`.

---

### Task 8: E2E green + 전체 회귀

**REQ-IDs:** REQ-F012-012, REQ-F012-013, REQ-F012-014, REQ-F012-015, REQ-F012-016

**Files:**
- Modify: Task 1의 3개 E2E (단언 미세조정)

- [ ] **Step 1: 빠른 회귀** — Run: `./gradlew :graph-rag-builder:test :test-generator:test :shared-model:test -PexcludeTags=integration`. Expected: PASS(REQ-F012-012 — egress/REQ-S015/변형/`EgressStubComposerTest` green).
- [ ] **Step 2: E2E 실행(SUT-boot)** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.capture.EgressStubBodyFidelity*'`(`-Dsut.jar`/`-Dsut.src` 게이트). Expected: 3층 PASS. 다른 worktree SUT 누수 없을 때만; hang 시 CI 위임.
- [ ] **Step 3: 자원 누수 검증** — E2E 종료 후 그 SUT PID 잔존 0(REQ-F012-016). 잔존 시 green 주장 금지.
- [ ] **Step 4: 매트릭스 012~016 🟡→🟢 + commit** — `test(egress): REQ-012 E2E 3층 green + 회귀 (REQ-F012-012~016)`.

---

## Self-Review

**1. Spec coverage:** 001/002/011→Task3. 003/005→Task4. 004→Task2. 006/007→Task5. 008/009→Task7. 010(폴백→Task3, loud→Task6). 012→Task4 Step7 + Task8 Step1. 013/014/015/016→Task1(red)+Task8(green). 017 🔵 deferred(태스크 없음 — 정상). 갭 없음.

**2. Placeholder scan:** 모든 step에 실제 코드/명령/기대 출력. E2E는 실재 harness 클래스(`Stage2AStringLiteralFuzzingE2E`/`EgressStatusAgnosticStubE2E`/`GeneratorVariantExclusionTest`/`BuilderIntegrationTest`)를 템플릿으로 명시.

**3. Type/signature consistency(grounding 반영):** `EgressStubComposer.compose`(4-arg, envelope 미포함) · `ErrorContractDescriptor(errorWhenPresent,...)` · `VariantOutcome`/`VariantInvoker.invoke`/`KeptVariant(label,body,sutStatus,branches)` · `buildEgressAssertionPaths`/`undrivenEgressBranches` pure helper · `discoveredBy="egress-assertion"`(Task5↔7) · `Provenance.CONTRACT` · `BodyShape.javaType()`(dtoFqn 없음) · `GenerationResult.files()`(generatedTestCount 없음) · `ExploredPath` 14-arg · loud는 `exploration-report.json`/`unsupportedShapes` 일관.

**4. 알려진 한계(명시):** envelope 변형 소비는 egress+envelope 겸비 sample SUT 부재로 unit/synthetic 검증에 한정(Task 4 노트). E2E generator 실행·loud 단언 대상(exploration-report.json)을 Task 1에 명시 반영.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-24-egress-stub-body-fidelity.md`.
권장: **Subagent-Driven** (superpowers:subagent-driven-development) — task마다 fresh subagent + 2-stage(spec/quality) 리뷰, task 간 검토. 절대경로 worktree·커밋 전 `git rev-parse --abbrev-ref HEAD` 확인 의무화.
