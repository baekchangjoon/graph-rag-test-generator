# 단계2 enum 상수 조합 응답 변형 fuzzing 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 또는 executing-plans로 task별 구현. 체크박스(`- [ ]`) 추적.

**Goal:** 응답 DTO enum 필드의 각 상수를 변형 stub으로 갈아끼우며 재탐색해, 그 enum으로 갈리는 SUT 분기의 모든 arm을 결정적(no-LLM)으로 연다.

**Architecture:** 단계1 B2 루프 수렴 후, enum 응답을 가진 외부 호출 path에 변형 루프를 돈다. `EnumResponseVariantGenerator`가 budget 우선순위로 변형을 만들고, `ExternalStubSynthesizer.registerVariant`가 trace-id 격리(otel `containing`/sleuth `equalTo`/none 순차교체)로 stub을 등록하며, 변형별 per-request 커버리지 delta로 새 arm 연 변형을 보존(`cumulativeCoverage` OR-병합).

**Tech Stack:** Java 23, WireMock(임베디드, withHeader/priority/removeStubMapping), Spoon, JaCoCo, JUnit5.

## Global Constraints

- **no-LLM·결정적**: 변형 생성·측정 순서 결정적(필드명 정렬 × 상수 선언순, label 식별자). 동일 commit → 동일 변형·stub·커버리지.
- **단계1 회귀 보존**: `Stage1ExternalStubSynthesisE2E`·`OrderExpressApiTest` 등 기존 테스트 green(fixture 갱신 포함).
- **격리 키**: otel `traceparent`는 전체 값(`00-<tid>-<sid>-<flags>`)이라 `containing(traceId)`로 매칭(절대 `equalTo(traceId)` 아님).
- **분모**: Must 9 + Should 2.
- 커밋 author/committer: `baekchangjoon <changjoon.baek@icloud.com>` (env vars).
- 신규 컴포넌트 패키지: `io.graphrag.builder.run`.
- REQ 출처: `docs/superpowers/requirements/2026-06-24-stage2-enum-response-fuzzing-requirements.md`.

---

### Task 1: SUT fixture 확장 (enum 응답 + 분기) + 기존 stub 갱신

**REQ-IDs:** REQ-011, REQ-005

**Files:**
- Create: `samples/order-service/src/main/java/io/graphrag/sample/orders/FulfillmentMode.java`
- Modify: `.../orders/InventoryClient.java` (InventoryResponse에 mode 추가), `.../orders/OrderController.java` (switch 분기)
- Modify: 기존 inventory stub 주는 테스트(`OrderExpressApiTest`, `Stage1ExternalStubSynthesisE2E`의 수동 stub 비교 경로)

**Interfaces:**
- Produces: `enum FulfillmentMode { STANDARD, EXPRESS_ONLY, BACKORDER }`, `record InventoryResponse(Integer available, FulfillmentMode mode)`.

- [ ] **Step 1: 실패 테스트** — order-service에 `mode` enum 응답으로 분기가 생겼음을 검증하는 통합 테스트(또는 기존 `OrderExpressApiTest`에 mode별 케이스). EXPRESS_ONLY+non-express→400, BACKORDER→409, STANDARD+부족→409 단언.

- [ ] **Step 2: red 확인** — Run: `./gradlew :samples:order-service:test`. Expected: FAIL(mode 없음/분기 없음).

- [ ] **Step 3: 구현** — `FulfillmentMode` 신규. `InventoryResponse(Integer available, FulfillmentMode mode)`. `OrderController.create`에 spec §문제의 switch(express 변수, ResponseStatusException, available<amount는 STANDARD arm 내부). 기존 inventory stub body를 `{"available":N,"mode":"STANDARD"}`로 갱신(`OrderExpressApiTest` 등).

- [ ] **Step 4: green 확인** — Run: `./gradlew :samples:order-service:test`. Expected: PASS(신규 + 기존 회귀).

- [ ] **Step 5: 커밋** — `test(fixture): order-service InventoryResponse.mode(FulfillmentMode) + switch 분기 REQ-011`.

---

### Task 2: E2E 스켈레톤 (outer loop)

**REQ-IDs:** REQ-001, REQ-002, REQ-004

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage2EnumResponseFuzzingE2E.java`

**Interfaces:**
- Consumes: 빌더 CLI, order-service SUT jar(Task 1 확장됨), 단계1 E2E 패턴.

- [ ] **Step 1: 실패 E2E 작성** — `@DisplayName("REQ-001: enum 변형으로 모든 arm 도달")` 등. `--external-stubs` 없이 order-service 빌드 → graph/커버리지에서 FulfillmentMode 3 arm 모두 covered, 변형 stub 캡처 SYNTHESIZED, 2회 빌드 동일.

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*Stage2EnumResponseFuzzingE2E'`. Expected: FAIL(변형 루프 미구현 → 첫 상수 1 arm만).

- [ ] **Step 3: 커밋** — `test(e2e): 단계2 enum 변형 E2E 스켈레톤(red) REQ-001,002,004`.

---

### Task 3: EnumResponseVariantGenerator

**REQ-IDs:** REQ-006, REQ-003

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EnumResponseVariantGenerator.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EnumResponseVariantGeneratorTest.java`

**Interfaces:**
- Produces: `record ResponseVariant(Map<String,String> enumOverrides, String label)`, `record VariantPlan(List<ResponseVariant> kept, int dropped)`, `VariantPlan generate(BodyShape shape, Map<String,List<String>> enumConstants, int budget)`.

- [ ] **Step 1: 실패 테스트**

```java
@Test void singleEnumYieldsConstantPerVariant() {
    BodyShape shape = new BodyShape("Inv", List.of(new BodyShape.BodyField("mode","p.FulfillmentMode")), false);
    VariantPlan p = new EnumResponseVariantGenerator().generate(shape,
        Map.of("p.FulfillmentMode", List.of("STANDARD","EXPRESS_ONLY","BACKORDER")), 32);
    // 첫 상수(STANDARD)=단계1 baseline 제외 → EXPRESS_ONLY,BACKORDER 2변형, label 결정적
    assertThat(p.kept()).extracting(ResponseVariant::label)
        .containsExactly("mode=BACKORDER","mode=EXPRESS_ONLY");  // 정렬 순서 확정(아래 구현이 결정)
}
@Test void budgetTruncationLoud() {
    VariantPlan p = new EnumResponseVariantGenerator().generate(shape, enums3, 1);
    assertThat(p.kept()).hasSize(1);
    assertThat(p.dropped()).isEqualTo(1);  // 절단량
}
@Test void singleFieldVariantsBeforeTwoWay() { /* enum 2필드: 단일 필드 변형이 조합보다 먼저 */ }
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EnumResponseVariantGeneratorTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — enum 필드 탐색(enumConstants 매칭). 단계1 baseline(모든 enum=정렬 첫 상수) 제외. 단일 필드 변형 먼저(필드명 정렬, 상수는 결정적 순서) → 2-way 카르테시안 → budget 자름 + dropped. label=정렬된 `field=CONST`.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): EnumResponseVariantGenerator budget 우선순위 변형 생성 REQ-006,003`.

---

### Task 4: TraceKey.matchFor (stub 매칭 조건)

**REQ-IDs:** REQ-007

**Files:**
- Modify: `graph-rag-builder/.../env/TraceKey.java` (+ Otel/Sleuth/No 구현)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/TraceKeyMatchForTest.java`

**Interfaces:**
- Produces: `StringValuePattern TraceKey.matchFor(String traceId)` + `String headerName()`. otel: `containing(traceId)`/`"traceparent"`, sleuth: `equalTo(traceId)`/`"X-B3-TraceId"`, none: `null`.

- [ ] **Step 1: 실패 테스트**

```java
@Test void otelMatchesContainingWithinFullTraceparent() {
    StringValuePattern p = new OtelTraceKey().matchFor("abc123");
    assertThat(p.match("00-abc123-span01-01").isExactMatch()).isTrue();   // 전체 traceparent에 substring
    assertThat(new OtelTraceKey().headerName()).isEqualTo("traceparent");
}
@Test void sleuthMatchesEqualTo() {
    assertThat(new SleuthTraceKey().matchFor("tid9").match("tid9").isExactMatch()).isTrue();
}
@Test void noneMatchForNull() { assertThat(new NoTraceKey().matchFor("x")).isNull(); }
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*TraceKeyMatchForTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `import static com.github.tomakehurst.wiremock.client.WireMock.*`. otel `containing`, sleuth `equalTo`, none null.

- [ ] **Step 4: green 확인** — Run: 위 + 기존 `*TraceKeyTest*` 회귀. Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(env): TraceKey.matchFor 모드별 WireMock 매처(otel containing) REQ-007`.

---

### Task 5: removeStub + registerVariant/removeVariant

**REQ-IDs:** REQ-008

**Files:**
- Modify: `graph-rag-builder/.../env/HttpCaptureServer.java` (removeStub)
- Modify: `graph-rag-builder/.../run/ExternalStubSynthesizer.java` (TraceKey 주입, registerVariant/removeVariant, 별도 Map)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ExternalStubVariantTest.java`

**Interfaces:**
- Consumes: `TraceKey.matchFor/headerName`(Task 4), `ShapeJsonSynthesizer`.
- Produces: `HttpCaptureServer.removeStub(UUID)`; `ExternalStubSynthesizer(HttpCaptureServer, ShapeJsonSynthesizer, TraceKey)`; `UUID registerVariant(String method, String pathLiteral, JsonNode body, String traceId)`; `void removeVariant(UUID)`.

- [ ] **Step 1: 실패 테스트** — 같은 (method,path)에 trace-id T1/T2 변형 2개 registerVariant → 둘 다 등록(멱등 차단 없음), T1 헤더 요청은 변형1, T2는 변형2 응답. removeVariant(id) 후 그 trace-id 요청은 전역(단계1) stub 응답.

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ExternalStubVariantTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `HttpCaptureServer.removeStub(UUID)` = `server.removeStubMapping`(WireMock API). `ExternalStubSynthesizer`에 TraceKey 주입 + 변형 추적 `Map<UUID,StubMapping>`. `registerVariant`: `get/post(urlPathEqualTo(path)).withHeader(traceKey.headerName(), traceKey.matchFor(traceId)).atPriority(전역보다 낮은 숫자).willReturn(200+body)` → `server.registerStub` → `mapping.getId()` 반환. none(matchFor null)이면 헤더 조건 없이 등록(전역-우선 priority). 단계1 `register`(전역 Set)는 무변경.

- [ ] **Step 4: green 확인** — Run: 위 + 기존 `*ExternalStubSynthesizer*`·`*HttpCaptureServer*` 회귀. Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): registerVariant/removeVariant trace-id 격리 + removeStub REQ-008`.

---

### Task 6: 변형 탐색 루프 (커버리지 유도·none 순차)

**REQ-IDs:** REQ-009, REQ-010

**Files:**
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EnumVariantReExploreTest.java`, `EnumVariantNoneModeTest.java`

**Interfaces:**
- Consumes: `EnumResponseVariantGenerator`(3), `ExternalStubSynthesizer.registerVariant`(5), invoke 전 trace-id(`OtelScope.traceId()` 등), `callSites`(단계1).

- [ ] **Step 1: 실패 테스트** — enum 응답 가진 path: B2 수렴 후 변형 루프가 각 변형 stub 등록·invoke·delta → 새 arm 연 변형 보존, cumulativeCoverage OR-병합(앞 변형 arm이 누적에 남음), budget 수렴. none 모드는 순차 교체(전역 보존).

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*EnumVariantReExploreTest' --tests '*EnumVariantNoneModeTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — B2 루프 직후, callSite.responseShape가 enum 필드를 가지면: `generate(shape, enumConstants, budget)` → for 변형 V: [otel/sleuth] 다음 invoke의 trace-id T 확보 → `registerVariant(method,path,body(V),T)` → invoke(T) → `coverage.dump(true)` delta → cumulativeCoverage **OR-병합**(리셋 금지) → 새 arm이면 보존; [none] registerVariant(헤더 없음) → invoke → removeVariant. budget 소진/전 변형까지. truncated loud.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): enum 변형 탐색 루프(커버리지 유도·OR-병합·none 순차) REQ-009,010`.

---

### Task 7: 변형 provenance 판정 갱신

**REQ-IDs:** REQ-004

**Files:**
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java` (provenanceOf)
- Test: `EnumVariantReExploreTest`에 provenance 케이스 추가

**Interfaces:**
- Produces: 변형 stub 경유 캡처도 `responseProvenance=SYNTHESIZED`.

- [ ] **Step 1: 실패 테스트** — 변형 stub(헤더 매칭, 전역 Set 미등록)으로 통과한 호출의 `CapturedHttpCall.responseProvenance==SYNTHESIZED`.

- [ ] **Step 2: red 확인** — Expected: FAIL(현재 isRegistered 전역만 → CAPTURED).

- [ ] **Step 3: 구현** — `provenanceOf`가 전역 `isRegistered` OR **변형 추적(현재/누적 변형 (method,path))** 이면 SYNTHESIZED. `ExternalStubSynthesizer`에 `isVariantRegistered(method,path)` 조회 추가.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `fix(run): provenanceOf가 변형 stub도 SYNTHESIZED 판정 REQ-004`.

---

### Task 8: E2E green + 매트릭스

**REQ-IDs:** REQ-001, REQ-002, REQ-004, REQ-005

- [ ] **Step 1: E2E 실행** — Run: `./gradlew :graph-rag-builder:test --tests '*Stage2EnumResponseFuzzingE2E'`. Expected: PASS(3 arm 도달). 실패 시 systematic-debugging(변형 루프/trace-id 매칭/커버리지 OR).

- [ ] **Step 2: 단계1 회귀** — Run: `./gradlew :graph-rag-builder:test --tests '*Stage1ExternalStubSynthesisE2E'`. Expected: PASS(REQ-005).

- [ ] **Step 3: 매트릭스 갱신** — REQ-001~011 Status 🟢, Coverage N/11. plan 체크박스 `[x]`.

- [ ] **Step 4: 커밋** — `test(e2e): 단계2 E2E green + REQ 매트릭스 마감`.

---

### Task 9: 전체 회귀

**REQ-IDs:** (전체)

- [ ] **Step 1: 전체 회귀** — Run: `./gradlew :graph-rag-builder:test :shared-model:test :samples:order-service:test`. Expected: green(skip 명시). order-service flake는 격리 재실행.

- [ ] **Step 2: 매트릭스 최종 확인** — REQ 대상 100% green ↔ 통과 테스트 대조.

- [ ] **Step 3: 커밋** — `test: 단계2 전체 회귀 green`.

---

## Self-Review

- **Spec coverage:** REQ-001~011 모두 Task 1~9 매핑.
- **Placeholder scan:** 각 step 실제 테스트/구현/명령. 없음.
- **Type consistency:** `ResponseVariant(enumOverrides,label)`/`VariantPlan(kept,dropped)`/`generate`/`TraceKey.matchFor:StringValuePattern`/`registerVariant:UUID`/`removeVariant(UUID)`/`removeStub(UUID)` task 간 일관.
