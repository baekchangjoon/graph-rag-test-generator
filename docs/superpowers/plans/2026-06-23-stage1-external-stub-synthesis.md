# 단계1 형상-only 외부 응답 stub 합성 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenAPI·LLM 없이 SUT 응답 DTO 형상에서 결정적으로 합성한 minimal valid 응답 stub으로 외부-의존 경로를 통과시킨다.

**Architecture:** 인덱싱에서 외부 호출 site의 (method, pathLiteral, 응답 BodyShape)를 추출 → 1차 invoke의 unmatched(404) 외부 호출을 trace-id로 귀속 → 형상에서 minimal JSON 합성 → WireMock 런타임 등록 → endpoint 재invoke(B2 루프, 상한 K). trace-id 격리는 모드-중립(otel/sleuth/none)으로 병렬-safe 설계, 실행은 직렬.

**Tech Stack:** Java 23, Spoon(정적 분석), WireMock(임베디드), Jackson, JUnit5.

## Global Constraints

- **LLM/HTTP 외부 의존 금지** — 단계1은 순수 정적·결정적. `io.graphrag.builder.index` 패키지 import 규칙(NoLlmDependencyTest) 유지.
- **결정성** — 동일 commit → 동일 stub(byte-동일). 캐시 없음.
- **기존 동작 보존** — `SampleInputSynthesizer`/`ResponseDtoIndexer` 기존 테스트 green 유지.
- **값 기본값**: Integer→1, String→`sample-<field>`, enum→정렬 첫 상수, Boolean→false (실제 `scalarValue` 동작).
- **모드 범위**: analysis 우선; attach는 회귀 없음 수준.
- 커밋 author/committer: `baekchangjoon <changjoon.baek@icloud.com>` (env vars).
- REQ 출처: `docs/superpowers/requirements/2026-06-23-stage1-external-stub-synthesis-requirements.md`.

---

### Task 1: E2E 스켈레톤 (outer loop, red 유지)

**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-011

**Files:**
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/Stage1ExternalStubSynthesisE2E.java`

**Interfaces:**
- Consumes: 빌더 CLI(`BuilderCli.main`), order-service SUT jar(`sut.jar` 시스템 프로퍼티, 기존 테스트와 동일 배선).
- Produces: REQ-001/002/003/011의 수용 단언(현재는 red — 합성 기능 미구현).

- [ ] **Step 1: 실패 E2E 작성** — `@DisplayName("REQ-001: 외부 stub 없이 형상 합성으로 외부 호출 통과")` 등 4개 메서드. `--external-stubs` 없이 order-service 빌드 → 산출 graph에서 `InventoryClient`의 `CapturedHttpCall`이 status 200 + `responseProvenance=SYNTHESIZED`인지, 외부 직후 분기 커버리지가 수동 stub 버전 이상인지 단언. (수동 stub 버전은 기존 `external.stubs` 배선 재사용.)

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests 'io.graphrag.builder.cli.Stage1ExternalStubSynthesisE2E'`. Expected: FAIL(합성 미구현 → 외부 호출 404 / provenance 필드 없음). **이 테스트는 Task 12까지 red 유지(스킵·약화 금지).**

- [ ] **Step 3: 커밋** — `git add` 테스트 파일; commit `test(e2e): 단계1 외부 stub 합성 E2E 스켈레톤(red) REQ-001~003,011`.

---

### Task 2: ShapeJsonSynthesizer 공유 헬퍼 추출

**REQ-IDs:** REQ-006

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ShapeJsonSynthesizer.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/SampleInputSynthesizer.java`(값 헬퍼 위임)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ShapeJsonSynthesizerTest.java`

**Interfaces:**
- Produces: `ShapeJsonSynthesizer(Map<String,List<String>> enumConstants)` + `JsonNode synthesizeBody(BodyShape shape)`. 값 규칙: scalar(Integer→1, String→`sample-<field>`, enum→정렬 첫 상수, Boolean→false), nested 객체 재귀, collection은 element 1개 배열. seed-row·Bean Validation·table 의존 없음.

- [ ] **Step 1: 실패 테스트 작성**

```java
@Test void integerFieldBecomes1() {
    BodyShape shape = new BodyShape("X", List.of(new BodyShape.BodyField("available", "Integer")), false);
    JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
    assertThat(body.get("available").asInt()).isEqualTo(1);
}
@Test void enumFieldBecomesSortedFirstConstant() {
    BodyShape shape = new BodyShape("X", List.of(new BodyShape.BodyField("status", "com.x.Status")), false);
    JsonNode body = new ShapeJsonSynthesizer(Map.of("com.x.Status", List.of("PENDING","ACTIVE"))).synthesizeBody(shape);
    assertThat(body.get("status").asText()).isEqualTo("ACTIVE"); // 정렬 첫 상수
}
@Test void collectionWrapsSingleElement() {
    BodyShape shape = new BodyShape("X", List.of(new BodyShape.BodyField("available","Integer")), true);
    JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
    assertThat(body.isArray()).isTrue();
    assertThat(body.get(0).get("available").asInt()).isEqualTo(1);
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ShapeJsonSynthesizerTest'`. Expected: FAIL(클래스 없음).

- [ ] **Step 3: 구현** — `SampleInputSynthesizer`의 `scalarValue(javaType, cons, fieldName)`·enum 선택·nested/collection 합성 로직을 `ShapeJsonSynthesizer`로 이동(seed/table/constraint 인자 제거). `SampleInputSynthesizer`는 새 클래스에 위임하도록 리팩터(값 합성만; seed-row는 자기 책임 유지). enum 정렬 첫 상수는 `enumConstants.get(type)`를 정렬 후 첫 요소.

- [ ] **Step 4: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ShapeJsonSynthesizerTest' --tests '*SampleInputSynthesizer*'`. Expected: 신규 PASS + 기존 SampleInputSynthesizer 테스트 전부 PASS(동작 보존).

- [ ] **Step 5: 커밋** — `feat(run): ShapeJsonSynthesizer 형상→minimal JSON 공유 헬퍼 추출 REQ-006`.

---

### Task 3: ResponseDtoIndexer 호출 site 추출

**REQ-IDs:** REQ-004, REQ-005

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ExternalCallSite.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ResponseDtoIndexer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ResponseDtoIndexerCallSiteTest.java`

**Interfaces:**
- Produces: `record ExternalCallSite(String httpMethod, String pathLiteral, Optional<BodyShape> responseShape)` + `ResponseDtoIndexer.extractCallSites(CtModel model): List<ExternalCallSite>`. 기존 `extract(...)`(필드명) 유지.

- [ ] **Step 1: 실패 테스트** — order-service 소스(또는 fixture)에서 `getForObject(baseUrl+"/inventory/stock?...", InventoryResponse.class)` → `(GET, "/inventory/stock", BodyShape(available:Integer))`. 배열 `Dto[].class` → component shape(collection=true). `exchange(uri, var, ...)` 변수 인자/제네릭 → `responseShape == empty`.

```java
@Test void extractsMethodPathAndShape() {
    List<ExternalCallSite> sites = new ResponseDtoIndexer().extractCallSites(model);
    ExternalCallSite inv = sites.stream().filter(s -> s.pathLiteral().equals("/inventory/stock")).findFirst().orElseThrow();
    assertThat(inv.httpMethod()).isEqualTo("GET");
    assertThat(inv.responseShape()).isPresent();
}
@Test void unextractableResponseTypeYieldsEmpty() { /* 제네릭/변수 인자 site → empty */ }
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ResponseDtoIndexerCallSiteTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `CLIENT_METHODS` 순회에서 (a) URL 인자의 정적 리터럴 path 구간 추출(문자열 concat에서 `/`로 시작하는 리터럴 토큰), (b) 메서드명→HTTP method, `exchange`는 인자의 `HttpMethod` enum 상수(변수면 empty), (c) class 리터럴 FQN→`BodyShapeExtractor.extract`(배열 `[]` strip 후 component, collection=true). 못 뽑으면 `responseShape=empty`. `SharedSpoonModel` 재사용.

- [ ] **Step 4: green 확인** — Run: 위 테스트 + 기존 `*ResponseDtoIndexer*` 회귀. Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(index): ResponseDtoIndexer 외부 호출 site(method/path/shape) 추출 REQ-004,005`.

---

### Task 4: TraceKey 모드-중립 trace-id 추출

**REQ-IDs:** REQ-007

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/env/TraceKey.java`(+ `OtelTraceKey`/`SleuthTraceKey`/`NoTraceKey`)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/TraceKeyTest.java`

**Interfaces:**
- Produces: `interface TraceKey { Optional<String> readTraceId(Map<String,String> outboundHeaders); }` + 3 구현 + `TraceKey.forMode(String traceMode)` 팩토리.

- [ ] **Step 1: 실패 테스트**

```java
@Test void otelParsesTraceparent() {
    assertThat(new OtelTraceKey().readTraceId(Map.of("traceparent","00-abc123-def-01")))
        .contains("abc123");
}
@Test void sleuthReadsB3() {
    assertThat(new SleuthTraceKey().readTraceId(Map.of("X-B3-TraceId","tid9"))).contains("tid9");
}
@Test void noneIsEmpty() { assertThat(new NoTraceKey().readTraceId(Map.of("traceparent","x"))).isEmpty(); }
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*TraceKeyTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — 헤더 lookup은 대소문자 무시. otel: `traceparent` split('-')[1]. sleuth: `X-B3-TraceId`. none: empty. `forMode`: "otel"→Otel, "sleuth"→Sleuth, else→No.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(env): TraceKey 모드-중립 outbound trace-id 추출 REQ-007`.

---

### Task 5: CapturedHttpCall provenance + 하위호환

**REQ-IDs:** REQ-011, REQ-012

**Files:**
- Modify: `shared-model/src/main/java/io/graphrag/model/CapturedHttpCall.java`
- Test: `shared-model/src/test/java/io/graphrag/model/CapturedHttpCallJsonRoundTripTest.java`

**Interfaces:**
- Produces: `CapturedHttpCall`에 `Provenance responseProvenance` 추가(enum `CAPTURED`/`SYNTHESIZED`). compat 생성자(기존 10-arg → `CAPTURED`). Jackson: 레거시 JSON(필드 없음)→`CAPTURED`.

- [ ] **Step 1: 실패 테스트**

```java
@Test void legacyJsonDefaultsToCaptured() throws Exception {
    String legacy = "{\"id\":\"h1\",\"pathId\":\"p1\",\"method\":\"GET\",\"urlPath\":\"/x\",\"responseStatus\":200}";
    CapturedHttpCall c = Json.mapper().readValue(legacy, CapturedHttpCall.class);
    assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
}
@Test void compatConstructorDefaultsCaptured() {
    CapturedHttpCall c = new CapturedHttpCall("h","p","GET","/x",Map.of(),null,200,"{}",List.of(),false);
    assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :shared-model:test --tests '*CapturedHttpCallJsonRoundTripTest'`. Expected: FAIL(컴파일/필드 없음).

- [ ] **Step 3: 구현** — `enum Provenance { CAPTURED, SYNTHESIZED }`. canonical 생성자에 11번째 필드 추가 + compact 생성자에서 `responseProvenance = responseProvenance == null ? Provenance.CAPTURED : responseProvenance`. 기존 10-arg 호출부 호환용 보조 생성자 추가(`this(..., Provenance.CAPTURED)`). Jackson은 null 기본 처리로 레거시 호환.

- [ ] **Step 4: green 확인** — Run: `./gradlew :shared-model:test`. Expected: PASS(기존 직렬화 테스트 포함).

- [ ] **Step 5: 커밋** — `feat(model): CapturedHttpCall.responseProvenance + 레거시 호환 REQ-011,012`.

---

### Task 6: RawHttpExchange.outboundTraceId + drain 헤더 추출 + 런타임 등록

**REQ-IDs:** REQ-007, REQ-008

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/explore/RawHttpExchange.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/env/HttpCaptureServer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/env/HttpCaptureServerTraceTest.java`

**Interfaces:**
- Produces: `RawHttpExchange`에 `String outboundTraceId` 추가. `HttpCaptureServer.registerStub(StubMapping)` 노출. `drainNewExchanges`가 `TraceKey`로 trace-id 채움. `HttpCaptureServer` 생성자/세터로 `TraceKey` 주입.

- [ ] **Step 1: 실패 테스트** — WireMock에 요청(traceparent 헤더 포함) → `drainNewExchanges().get(0).outboundTraceId()`가 trace-id. `registerStub`로 등록한 매핑이 다음 요청에 200 반환.

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*HttpCaptureServerTraceTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `RawHttpExchange`에 필드 추가(모든 생성자 호출부 갱신 — `EndpointExplorationRunner.captureHttpCalls` 포함). `drainNewExchanges`에서 `event.getRequest().getHeaders()`를 Map으로 변환해 `traceKey.readTraceId(...)`로 채움(없으면 빈 문자열). `registerStub` = `server.addStubMapping` 위임.

- [ ] **Step 4: green 확인** — Run: 위 + 기존 `*HttpCaptureServer*` 회귀. Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(env): outbound trace-id 귀속 + 런타임 stub 등록 REQ-007,008`.

---

### Task 7: CallSiteMatcher

**REQ-IDs:** REQ-009

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/CallSiteMatcher.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/CallSiteMatcherTest.java`

**Interfaces:**
- Produces: `CallSiteMatcher.match(String method, String urlPath, List<ExternalCallSite> sites): Optional<ExternalCallSite>`. segment-경계 `endsWith` + 최장 path 우선 + method 일치.

- [ ] **Step 1: 실패 테스트**

```java
@Test void matchesByPathAndMethod() {
    var site = new ExternalCallSite("GET","/inventory/stock",Optional.of(shape));
    assertThat(CallSiteMatcher.match("GET","/inventory/stock", List.of(site))).contains(site);
}
@Test void prefersLongerPathOnConflict() { /* /a/b 와 /b 둘 다 endsWith "/b" → /a/b 우선 */ }
@Test void methodMismatchNoMatch() {
    var site = new ExternalCallSite("POST","/x",Optional.of(shape));
    assertThat(CallSiteMatcher.match("GET","/x", List.of(site))).isEmpty();
}
```

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*CallSiteMatcherTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — method 일치 필터 → urlPath가 site.pathLiteral로 segment 경계 endsWith(또는 동등) → 후보 중 pathLiteral 최장 → 동률 첫 매치(로그). segment 경계: `urlPath.equals(p) || urlPath.endsWith("/"+p) || urlPath.endsWith(p)`에서 `/` 경계 검증.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): CallSiteMatcher segment endsWith + specificity REQ-009`.

---

### Task 8: ExternalStubSynthesizer

**REQ-IDs:** REQ-006, REQ-008

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ExternalStubSynthesizer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ExternalStubSynthesizerTest.java`

**Interfaces:**
- Consumes: `ShapeJsonSynthesizer`(Task 2), `HttpCaptureServer.registerStub`(Task 6).
- Produces: `ExternalStubSynthesizer(HttpCaptureServer server, ShapeJsonSynthesizer shapes)` + `boolean register(String method, String pathLiteral, BodyShape shape)`(이미 등록 시 false, 멱등). 내부에서 `synthesizeBody` → WireMock 200 StubMapping(`urlPathEqualTo` + method).

- [ ] **Step 1: 실패 테스트** — `register("GET","/inventory/stock",shape)` 후 그 path 요청이 200 + `{"available":1}`. 동일 (method,path) 재호출 시 false.

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ExternalStubSynthesizerTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — 등록된 (method,pathLiteral) Set 보관(멱등). `StubMapping`: `get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withHeader("Content-Type","application/json").withBody(json))` (method별 분기). `server.registerStub(mapping)`.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): ExternalStubSynthesizer 형상→stub 런타임 등록(멱등) REQ-006,008`.

---

### Task 9: StaticIndexBundle/explore 배선

**REQ-IDs:** REQ-004

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`(StaticIndexBundle, indexStatically, explore 시그니처)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`(callSites 수신 필드)
- Test: 기존 빌드/통합 테스트 회귀

**Interfaces:**
- Produces: `StaticIndexBundle`에 `List<ExternalCallSite> callSites` 추가; `indexStatically()`가 `ResponseDtoIndexer.extractCallSites`로 채움; `EndpointExplorationRunner`가 생성자로 수신.

- [ ] **Step 1: 수신 필드 추가** — `EndpointExplorationRunner`에 `List<ExternalCallSite> callSites` 필드 + 생성자 인자(기존 호환 생성자는 `List.of()` 위임).

- [ ] **Step 2: 번들/배선** — `StaticIndexBundle` 레코드에 필드 추가, `indexStatically()`에서 `extractCallSites(sharedModel)` 채움, `explore()`가 runner에 전달. (SharedSpoonModel 재사용.)

- [ ] **Step 3: 컴파일/회귀** — Run: `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:test --tests '*BuilderIntegrationTest'`. Expected: PASS(파급 호출부 전부 갱신, 기존 회귀 green).

- [ ] **Step 4: 커밋** — `refactor(cli): StaticIndexBundle/explore에 외부 callSites 배선 REQ-004`.

---

### Task 10: 재탐색 루프 (B2)

**REQ-IDs:** REQ-008, REQ-014

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ExternalStubReExploreTest.java`

**Interfaces:**
- Consumes: `CallSiteMatcher`(7), `ExternalStubSynthesizer`(8), unmatched 캡처(6), `callSites`(9).
- Produces: endpoint invoke 후 unmatched(404) 외부 호출 → 합성·등록 → 재invoke(K회 수렴, baseline 리셋). none 모드 직렬 폴백.

- [ ] **Step 1: 실패 테스트** — 외부 호출이 404로 떨어진 1차 outcome → 루프가 stub 등록 후 재invoke → 외부 호출 200. K회 내 수렴(새 stub 없으면 종료), 멱등 재등록 없음. (가짜 SUT/HttpCaptureServer로 통합 수준.)

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ExternalStubReExploreTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — 설계 §재탐색 루프 의사코드 구현: `outcome.httpExchanges().filter(status==404)` → `CallSiteMatcher.match` → shape 있으면 `ExternalStubSynthesizer.register`(멱등) → 등록된 게 있으면 `coverage.dump(true)`(baseline 리셋) 후 재invoke. 상한 K=3 상수. none 모드는 trace-id 없이 drain 전체를 해당 요청 unmatched로(직렬 전제).

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): B2 외부 stub 캡처→합성→재탐색 루프 REQ-008,014`.

---

### Task 11: loud-fail 리포트 + provenance 태깅

**REQ-IDs:** REQ-010, REQ-011

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`(captureHttpCalls provenance)
- Modify: 재탐색 루프(loud-fail 기록)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ExternalStubLoudFailTest.java`

**Interfaces:**
- Produces: 합성 stub 경유 호출은 `CapturedHttpCall.responseProvenance=SYNTHESIZED`(등록한 (method,path) 집합 대조). loud-fail 4종 로그/리포트 기록.

- [ ] **Step 1: 실패 테스트** — 미추출 응답 타입 site → `unwired-external-dep ... fallback=stage3` 기록. 합성 stub 경유 캡처 → provenance SYNTHESIZED.

- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*ExternalStubLoudFailTest'`. Expected: FAIL.

- [ ] **Step 3: 구현** — `captureHttpCalls`에서 (method, urlPath)가 합성 등록 집합에 있으면 `SYNTHESIZED` 부여. 재탐색 루프에서 `responseShape.empty`/매칭 실패/합성 실패/등록 후 미도달을 각각 WARN + 리포트 라인으로 기록.

- [ ] **Step 4: green 확인** — Expected: PASS.

- [ ] **Step 5: 커밋** — `feat(run): loud-fail surface 4종 + provenance 태깅 REQ-010,011`.

---

### Task 12: E2E green + 매트릭스 갱신

**REQ-IDs:** REQ-001, REQ-002, REQ-003, REQ-011

**Files:**
- Modify: `Stage1ExternalStubSynthesisE2E.java`(필요 시 단언 정밀화)
- Modify: 요구사항명세 매트릭스(🟡→🟢)

- [ ] **Step 1: E2E 실행** — Run: `./gradlew :graph-rag-builder:test --tests '*Stage1ExternalStubSynthesisE2E'`. Expected: PASS(Task 1의 red가 green으로). 실패 시 systematic-debugging으로 재탐색 루프/매칭/합성 점검.

- [ ] **Step 2: 결정성 확인** — 2회 실행 byte-동일 stub 단언 green.

- [ ] **Step 3: 매트릭스 갱신** — REQ-001~014 Status를 실제 테스트 결과로 🟢 갱신, Coverage 줄 N/14.

- [ ] **Step 4: 커밋** — `test(e2e): 단계1 E2E green + REQ 매트릭스 100% 갱신`.

---

### Task 13: attach 회귀 + none 모드 확인 + 전체 회귀

**REQ-IDs:** REQ-013, REQ-014

**Files:**
- Test: 기존 attach 통합 테스트, `ExternalStubNoneModeTest`

- [ ] **Step 1: none 모드 테스트** — `--trace-mode none`에서 합성·통과 직렬 동작 단언(`ExternalStubNoneModeTest`).

- [ ] **Step 2: attach 회귀** — Run: 기존 attach/HttpCapture 통합 테스트 suite. Expected: green 유지.

- [ ] **Step 3: 전체 회귀** — Run: `./gradlew test`. Expected: 전 모듈 green(skip은 명시).

- [ ] **Step 4: 매트릭스 최종 확인** — REQ 대상 100% green, 각 green REQ ↔ 통과 테스트 대조.

- [ ] **Step 5: 커밋** — `test: none 모드 + attach 회귀 + 전체 회귀 green REQ-013,014`.

---

## Self-Review (작성자 점검)

- **Spec coverage:** REQ-001~014가 Task 1~13에 모두 매핑됨(매트릭스 테스트명 ↔ task). 누락 없음.
- **Placeholder scan:** 각 step에 실제 테스트/구현 코드 또는 구체 명령. "적절한 에러처리" 류 없음.
- **Type consistency:** `ExternalCallSite(httpMethod, pathLiteral, responseShape)`, `ShapeJsonSynthesizer.synthesizeBody`, `TraceKey.readTraceId`, `CapturedHttpCall.Provenance`, `ExternalStubSynthesizer.register`, `CallSiteMatcher.match`가 task 간 일관.
