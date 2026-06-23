# 단계2 — enum 상수 조합 응답 변형 fuzzing (설계)

- 일자: 2026-06-24
- 브랜치: feat-stage2-enum-response-fuzzing
- 선행: 단계1(형상-only 외부 응답 stub 합성, PR #88 머지). 본 단계는 단계1의 `ExternalStubSynthesizer`·B2 재탐색 루프·`TraceKey`·per-request 커버리지 위에 얹는다.
- 결정 경로: 사용자가 "단계2를 네 권장·추천으로 진행"으로 설계 결정을 위임. 본 설계는 그 권장안이며 3벤더 design-doc 리뷰로 검증한다.

## 비목표 경계 (먼저 못박는다)

- **status-스타일 자유 String 리터럴 변형** — 응답 String 필드의 분기 리터럴(`if (resp.status().equals("APPROVED"))`)을 소비 코드에서 추출해 변형하는 것은 **단계2의 후속**(별도 작업). 본 단계는 **enum 타입 응답 필드**(상수 집합이 정적으로 완전한 것)만 다룬다.
- **concolic 숫자 경계 변형** — 응답 숫자 필드의 경계값(ASM+Z3)은 **별도**(단계2 이후).
- **LLM / OpenAPI 응답 합성** — 단계3.
- **중첩 객체 응답 DTO** — 단계1대로 `unsynthesizable-shape` loud-fail 유지(변형 대상 아님).
- **병렬 탐색 실행** — 직렬 실행 유지. 격리 메커니즘만 trace-id로 병렬-safe.

## 전제 (Prerequisites)

- **otel 모드 격리는 SUT의 OTEL agent가 outbound HTTP(RestTemplate 등)에 W3C `traceparent`를 자동 전파하는 환경을 전제한다**(otel-javaagent + HTTP instrumentation enabled). `InventoryClient`는 단순 `new RestTemplate()`이라 전파는 agent 계측에 의존한다. 미전파/`--trace-mode none` 환경에서는 **none 모드와 동일하게 격리 불가 → 변형 순차 교체**로 동작한다(아래 §격리 키).
- 응답 DTO FQN을 호출 site에서 못 뽑으면(`ResponseDtoIndexer`가 `getForObject/postForObject/getForEntity/postForEntity/exchange` 외 패턴, 또는 제네릭/Feign/WebClient은 미지원 — 단계1 한계) `responseShape=empty`라 **변형도 생성되지 않는다**(정상, loud-fail은 단계1이 이미 기록).

## 문제

단계1은 enum 응답 필드를 **정렬 첫 상수 1종**으로만 채운다. 그러면 그 enum 값으로 갈리는 SUT의 외부-응답-의존 분기는 **1 arm만** 통과하고 나머지는 미탐색이다. 예(order-service 확장 검증 SUT, 실제 Java):

```java
boolean express = "EXPRESS".equals(request.type());
InventoryClient.InventoryResponse stock = inventory.check(request.type());   // {available, mode: FulfillmentMode}
switch (stock.mode()) {
    case BACKORDER -> throw new ResponseStatusException(HttpStatus.CONFLICT, "backordered");    // 단계1 미도달
    case EXPRESS_ONLY -> { if (!express) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "express only"); }  // 단계1 미도달
    case STANDARD -> { if (stock.available() < request.amount()) throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock"); }  // 단계1이 정렬 첫 상수면 여기만(기존 available 분기 보존)
}
```

그러나 **enum은 상수 집합이 정적으로 완전히 알려져 있다**(`enumConstants`, 이미 인덱싱됨). LLM도 OpenAPI도 없이 **모든 상수를 변형 stub으로 갈아끼우면 모든 arm을 결정적으로** 연다. 이것이 단계1 한계의 결정적·no-LLM 해소다.

## 설계

### 핵심 데이터 흐름 (단계1 B2 루프 → 변형 루프)

```
[단계1] B2 루프: 외부 호출 path에 형상 첫-상수 stub 등록 → 통과(1 arm)

[단계2] 변형 루프 (B2 수렴 후, enum 응답 가진 path에 대해):
  EnumResponseVariantGenerator(BodyShape, enumConstants) → 변형 목록(결정적, budget cap)
  for 변형 V in 변형들:
      [otel/sleuth] 다음 invoke의 trace-id T 확보 → stub(V)를 (method,path,withHeader trace-id=T) 등록
                    → invoke(T 주입) → per-request 커버리지 delta
      [none]        기존 stub remove → stub(V) 등록 → invoke → delta → (다음 변형 위해) 복원
      새 분기(arm) 연 변형 보존 → 시드/생성 테스트(provenance=SYNTHESIZED)
  budget 소진 또는 전 변형 시도까지
```

### 컴포넌트 분해

신규 컴포넌트는 `io.graphrag.builder.run`(단계1 자산과 동일 패키지). 신규 의존 없음(단계1 자산 + 기존 WireMock/Spoon).

#### 1. `EnumResponseVariantGenerator` — 신규 (`io.graphrag.builder.run`)

```java
record ResponseVariant(java.util.Map<String,String> enumOverrides, String label) {}  // label = 정렬된 "field=CONST[,field2=CONST2]" (결정적 식별자, 측정·로그·dedupe 키)
record VariantPlan(java.util.List<ResponseVariant> kept, int dropped) {}
VariantPlan generate(BodyShape shape, Map<String,List<String>> enumConstants, int budget);
```

- 응답 BodyShape의 **enum 타입 필드**(javaType이 `enumConstants`에 있거나 simple-name 매칭)를 찾는다. `enumConstants`는 입력 측과 동일 추출기(SUT 소스의 모든 enum 타입)라 응답 DTO enum도 잡힌다.
- **budget 우선순위 알고리즘(결정적, 명세)**:
  1. **단일 필드 변형 먼저** — 필드명 정렬 순으로, 각 필드의 모든 상수(선언순)를 단독 변형으로 생성(다른 enum 필드는 단계1 기본=정렬 첫 상수 고정). → 총 ΣMᵢ개. 이로써 **각 상수가 budget 내에서 최소 1회 보장**.
  2. budget 잔여분이 있으면 **2-way 카르테시안 조합**을 필드 쌍 정렬 순으로 추가.
  3. 생성 목록을 budget 앞에서 자르고 `dropped` 카운트. (3-way+는 단계2 비목표.)
- 절단 시 **loud 로그**(`response-variant-budget-truncated: <path> kept=N dropped=M`) — silent cap 금지.
- `label`이 결정성·dedupe·측정 식별자. 단계1 기본 변형(모든 enum=정렬 첫 상수)은 생성 목록에서 제외(이미 단계1이 측정).
- 비-enum 필드는 단계1 기본값 고정(`ShapeJsonSynthesizer` baseline JSON에 enum override만 적용해 변형 body 생성).

#### 2. `ExternalStubSynthesizer` 확장 (단계1 단일 등록 지점)

```java
// 생성자에 TraceKey 추가 주입(현재 HttpCaptureServer.traceKey는 private·getter 없음 → ExternalStubSynthesizer가 직접 보유)
ExternalStubSynthesizer(HttpCaptureServer server, ShapeJsonSynthesizer shapes, TraceKey traceKey);
boolean register(String method, String pathLiteral, BodyShape shape);                       // 단계1 (전역, registered Set 멱등)
StubId registerVariant(String method, String pathLiteral, JsonNode body, String traceId);   // 단계2 (격리), 등록된 StubMapping UUID 반환
void removeVariant(StubId id);                                                               // none 모드 교체용
```

- **멱등 키 분리(중요)**: 단계1 전역 stub의 `registered` Set(`"METHOD /path"`)은 그대로 유지. 변형 stub은 같은 (method,path)에 여러 개 공존해야 하므로 `registered` Set을 쓰지 않고 **별도 `Map<StubId, StubMapping>`** 로 추적해 `removeVariant`에서 UUID로 정확히 제거.
- `registerVariant`: 변형 body를 `(method, urlPathEqualTo(path), withHeader(헤더명, traceKey.matchFor(traceId)))` 매칭 + **WireMock priority를 단계1 전역 stub보다 높게(낮은 숫자)** 등록 → 격리 요청은 변형이, 그 외(미주입)는 전역 stub이 매칭. `StubMapping.getId()`(UUID)를 `StubId`로 반환.
- otel/sleuth: 변형별 stub 공존, 요청 trace-id로 격리.
- none: trace-id 없음 → 변형마다 `registerVariant`(헤더 조건 없음 = 전역 우선 stub) → invoke → `removeVariant`로 **순차 교체**(직렬). **단계1 전역 stub은 삭제하지 않는다**(priority로 변형이 먼저 매칭, 변형 제거 시 전역으로 복원).
- `HttpCaptureServer`에 **`removeStub(StubId)`(= `server.removeStubMapping(uuid)`) 신규 노출** — 현재 `registerStub`만 있으므로 제거 API 추가.

#### 3. `TraceKey` 확장 — stub 매칭 조건 생성

단계2는 변형 stub을 invoke **전에** 그 invoke의 trace-id로 등록한다. 빌더는 invoke 전에 trace-id를 안다 — `SqlCaptureBackend.Scope`/`OtelSpanCapture.begin()`이 요청별 traceparent를 invoke 전에 발급·주입하고(`OtelScope.traceId()`), SUT agent가 그 trace를 outbound로 전파(같은 trace-id). `TraceKey`에 매칭 조건 생성기 추가:

```java
// 반환은 WireMock StringValuePattern (null이면 헤더 조건 없음 = none)
com.github.tomakehurst.wiremock.matching.StringValuePattern matchFor(String traceId);
String headerName();   // otel: "traceparent", sleuth: "X-B3-TraceId", none: 미사용
```

- **otel**: outbound `traceparent` 값은 `00-<traceId>-<spanId>-<flags>` **전체 문자열**이라 `equalTo(traceId)`로는 **절대 매칭 안 됨**(critical). → `WireMock.containing(traceId)`(전체 값에 trace-id substring 포함 검증).
- **sleuth**: `X-B3-TraceId` 값이 trace-id 자체 → `WireMock.equalTo(traceId)`.
- **none(`NoTraceKey`)**: `matchFor`는 `null`(헤더 조건 없음). 변형은 전역-우선 priority로만 격리(순차 교체).

#### 4. 변형 탐색 루프 (`EndpointExplorationRunner`)

단계1 B2 루프(`run()`의 1차 explore 직후) **수렴 후**, enum 응답을 가진 호출 site에 대해 변형 루프를 돈다.

- 입력 fuzzer(`CoverageGuidedFuzzer`)는 **body 변이 전용**이라 직접 못 쓴다(설계상 별도 축). 변형 루프는 단계1 B2 루프와 같은 endpoint-scoped 패턴으로 신설하되 "변이 좌표=응답 변형".
- 각 변형마다: 변형 stub 등록 → endpoint invoke → `coverage.dump(true)`로 **per-variant delta** 측정 → 직전 누적 대비 **새 분기 arm**이 열렸으면 보존.
- **`cumulativeCoverage` 누적(중요)**: 변형 루프는 B2 루프와 달리 `cumulativeCoverage`를 변형마다 리셋하지 **않고 OR-병합**한다(negative-auth/B1 패스와 동일). 그래야 앞선 변형이 연 arm이 최종 `report()`에 남고 `missedBranches`에서 빠진다. (B2 루프의 라운드별 `cumulativeCoverage = new ExecutionDataStore()` 리셋과 다른 점 — 변형 루프 진입 시점의 누적을 보존하면서 각 변형 delta를 더한다.)
- **budget**: `responseVariantBudget`(기본 = generator budget cap). 소진 시 종료 + 로그.
- 보존된 변형 = 그 path의 추가 stub → 생성 테스트(provenance=SYNTHESIZED, 단계1 경로 재사용).

#### 5. provenance / 결정성 / loud-fail

- 변형 stub 캡처도 `responseProvenance=SYNTHESIZED`. 단계1 `provenanceOf()`는 `stubSynthesizer.isRegistered(method, pathLiteral)`(전역 Set)만 검사하므로, **변형 stub(헤더 매칭, 전역 미등록)도 SYNTHESIZED로 판정되도록 갱신**한다 — 변형 추적 Map에 (method,path)가 있거나 현재 활성 변형이면 SYNTHESIZED. (none 모드에서 전역 stub을 삭제하지 않으므로 전역+변형 모두 SYNTHESIZED 일관.)
- 결정성: 변형 생성·측정 순서 결정적(필드/상수 정렬). 동일 commit → 동일 변형 집합·동일 stub·동일 커버리지.
- loud-fail: budget 절단(`response-variant-budget-truncated`), enum 필드 없는 path는 변형 0(정상, 단계1 단일 stub만).

### 격리 키 (단계1 `TraceKey` 재사용)

- **otel/sleuth**: 변형별 stub을 trace-id로 격리(공존 가능, `withHeader` + 전역보다 높은 priority). 빌더가 invoke 전 발급한 trace-id를 변형에 바인딩.
- **none**: trace-id 없음 → 변형마다 순차 stub 교체(직렬). **단계1 전역 stub은 보존**하고 변형 stub만 전역-우선 priority로 등록·제거(변형 제거 시 전역으로 자동 복원). 동작은 하되 느리다.

### 모드 범위 (attach)

단계1과 동일하게 **analysis 모드 우선**. attach 모드는 `HttpCaptureServer`의 per-run token path-prefix 필터가 있어 런타임 `registerVariant`+trace 헤더 매칭의 호환은 **별도 검증**(1차는 analysis E2E, attach는 회귀 없음 수준). attach에서 변형 fuzzing은 본 단계 비목표.

### 결정성·budget 상세

- enum 필드 N개, 각 평균 M 상수 → M^N 변형. budget cap(32)으로 절단. 절단 시 어떤 조합을 우선? **각 enum의 모든 상수가 최소 1회 등장하도록 우선**(coverage 관점: 단일 필드 변형 우선 → 조합은 budget 여유 시). 절단량 loud 로그.

## 검증 SUT (갭 — fixture 확장)

`samples/order-service`의 `InventoryResponse(Integer available)`에 **enum 필드 추가** + `OrderController`가 그 enum으로 분기:

- `enum FulfillmentMode { STANDARD, EXPRESS_ONLY, BACKORDER }` (신규)
- `record InventoryResponse(Integer available, FulfillmentMode mode)` (확장)
- `OrderController.create`의 `switch (stock.mode())` (위 §문제의 실제 Java). **기존 `available < amount → 409`는 STANDARD arm 내부로 이동해 동작 보존**.
- **기존 테스트 동반 갱신(중요)**: `InventoryResponse`에 `mode` 추가 시, 기존 inventory stub을 주는 테스트의 응답 body를 `{"available":N,"mode":"STANDARD"}`로 갱신해야 한다 — `Stage1ExternalStubSynthesisE2E`(수동 stub 비교 경로), `OrderExpressApiTest`(`/inventory/stock` stub). `mode` 누락 시 역직렬화 null → switch NPE. 이 갱신은 단계2 plan의 첫 task(fixture)에 포함하고, 단계1 E2E green을 재확인한다.
- **budget 절단 재현용**: 단일 enum(`FulfillmentMode`, 3상수)만으로는 budget(32) 미초과라 절단 E2E를 못 만든다. 절단 검증은 (a) **테스트 전용 작은 budget 주입**(예 `responseVariantBudget=2`로 generator 단위 테스트/통합) 또는 (b) **enum 필드 2개 fixture**(`FulfillmentMode` × `ShippingClass` 등) 중 **(a)를 채택**(SUT 과확장 회피, 결정적). E2E는 단일 enum 3-arm 도달, budget 절단은 generator unit + 작은-budget 통합으로 검증.

이로써: 단계1은 `mode` 첫 상수 1 arm, **단계2는 STANDARD/EXPRESS_ONLY/BACKORDER 3 arm 모두 도달**.

## 테스트 전략 (double-loop TDD)

### E2E / 수용 (outer loop)

1. **enum 변형으로 모든 arm 도달** — Given order-service를 단계2 빌더로 `--external-stubs` 없이 탐색, When `FulfillmentMode` 3상수를 변형 stub으로 등록·재invoke, Then graph의 POST `/api/orders` 결과 커버리지에 `switch`의 STANDARD/EXPRESS_ONLY/BACKORDER 3 arm이 모두 covered(또는 `missedBranches`에서 빠짐)이고, 단계1(첫 상수만) 대비 arm 수가 증가한다.
2. **결정성** — 2회 빌드 동일 변형 집합(label 동일)·동일 커버리지.
3. **budget 절단 loud** — 작은 budget(예 2) 주입 시 `response-variant-budget-truncated ... kept=2 dropped=M` 기록(generator unit + 통합).
4. **provenance** — 변형 stub 경유 캡처 `responseProvenance=SYNTHESIZED`.
5. **단계1 회귀** — `Stage1ExternalStubSynthesisE2E`(mode stub 갱신 후) green 유지.

### Unit (inner loop)

- `EnumResponseVariantGenerator`: 단일 enum N상수→N변형, 다중 카르테시안+budget 절단+loud, 결정적 순서.
- `ExternalStubSynthesizer.registerVariant`/`removeVariant`: trace-id 격리 매칭, none 순차 교체, priority.
- `TraceKey.matchFor`: otel/sleuth 매칭 조건.
- 변형 루프: 새 arm 연 변형 보존, budget 수렴, 단계1 단일 stub과 공존.

### 완료 정의

- 단계2 E2E green + unit green + 요구사항명세 REQ 매트릭스 100%(Must + 미연기 Should).
- 단계1 회귀 green. 게이트: spec-compliance + code-quality 리뷰, 회귀 green, 문서 동기화.

## 대안 검토

- **CoverageGuidedFuzzer에 응답 변형 축 추가**(입력×응답 단일 좌표 일반화): 가장 통합적이나 fuzzer 추상화 대수술 → 단계2 범위 폭발. 별도 변형 루프가 외과적.
- **무차별 전 조합 stub**(커버리지 유도 없이): budget 폭발 + 의미 없는 변형 낭비. 커버리지 유도가 효율적.
- **선택: B2 루프 확장 + enum 변형 + 커버리지 유도 + trace-id 격리** — 단계1 자산 최대 재사용, 결정적, no-LLM.

## 한계 (단계2 후속·단계3)

- 다중 enum 카르테시안은 budget 절단(완전 조합 미보장 — loud 기록).
- status 자유 String/숫자 경계는 단계2 후속(리터럴 추출/concolic).
- 응답 enum ↔ DB 시드 정합(외부 응답 id가 DB 엔티티와 일치해야 하는 경로)은 범위 밖.

## 리뷰 반영

3벤더 design-doc 리뷰(Claude Sonnet / Gemini 3.5 Flash High / Cursor auto, 초안 전원 needs_revision) 반영:

- **반영(critical)**: otel `traceparent` 매칭은 전체 값(`00-<tid>-...`)이라 `equalTo(tid)` 불가 → `containing(traceId)` 명시(Sonnet I1·Gemini I1·Cursor I12). `StubMatchCondition`을 WireMock `StringValuePattern`으로 구체화(전원). `ExternalStubSynthesizer`에 `TraceKey` 직접 주입(HttpCaptureServer.traceKey private, Gemini I3). `HttpCaptureServer.removeStub` 신규 추가(Gemini I2).
- **반영(important)**: 변형 stub은 `registered` Set이 아닌 별도 `Map<StubId,StubMapping>`으로 추적(멱등 키 충돌, Sonnet I3). `cumulativeCoverage` 변형 간 OR-병합(리셋 금지, Sonnet I2). SUT fixture 확장이 `Stage1ExternalStubSynthesisE2E`·`OrderExpressApiTest` stub을 깸 → `{available,mode}` 갱신 + available 분기 STANDARD arm 이동 명시(Sonnet I4). budget 우선순위 알고리즘(단일 필드 ΣM → 2-way → 절단) 구체화(Sonnet I5·Gemini I6·Cursor I13). `provenanceOf`가 변형 stub도 SYNTHESIZED 판정하도록 갱신(Cursor I10). budget 절단 재현은 작은-budget 주입 채택(SUT 과확장 회피, Cursor I9). E2E 항목 Given-When-Then화(Sonnet I9). otel agent outbound 전파 전제 추가(Sonnet I8). none 모드 전역 stub 보존+priority(Sonnet I7). 패키지 `io.graphrag.builder.run`(Gemini I5). switch pseudocode를 실제 Java로 정정(Gemini I4). attach 모드 범위 명시(Cursor I11).
- **기각**: 없음(전부 명세 보강으로 타당, 단계2 범위 내).
