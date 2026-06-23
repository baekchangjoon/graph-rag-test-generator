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

## 문제

단계1은 enum 응답 필드를 **정렬 첫 상수 1종**으로만 채운다. 그러면 그 enum 값으로 갈리는 SUT의 외부-응답-의존 분기는 **1 arm만** 통과하고 나머지는 미탐색이다. 예(order-service 확장 검증 SUT):

```java
InventoryResponse stock = inventory.check(type);     // {available, mode: FulfillmentMode}
switch (stock.mode()) {
    case BACKORDER   -> throw 409;                    // 단계1 미도달
    case EXPRESS_ONLY -> if (!express) throw 400;      // 단계1 미도달
    case STANDARD    -> proceed;                       // 단계1이 정렬 첫 상수면 여기만
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

모두 `io.graphrag.builder`. 신규 의존 없음(단계1 자산 + 기존 WireMock/Spoon).

#### 1. `EnumResponseVariantGenerator` — 신규

```java
record ResponseVariant(java.util.Map<String,String> enumOverrides, String label) {}
List<ResponseVariant> generate(BodyShape shape, Map<String,List<String>> enumConstants, int budget);
```

- 응답 BodyShape의 **enum 타입 필드**(javaType이 `enumConstants`에 있거나 simple-name 매칭)를 찾고, 각 필드의 각 상수로 변형을 만든다.
- **단일 enum 필드**: 상수 수만큼 변형(첫 상수=단계1 기본은 제외하거나 포함 — 포함하되 중복 측정 회피).
- **다중 enum 필드**: 카르테시안 곱. **budget cap**(기본 32). 초과분은 **잘라내되 loud 로그**(`response-variant-budget-truncated: <path> kept=N dropped=M`) — silent cap 금지.
- **결정적 순서**: 필드명 정렬 × 상수 정렬(단계1 enum 선언순과 일관되게 — 단, 변형은 *모든* 상수를 도니 순서는 측정 순서에만 영향). `index`로 다양화.
- 비-enum 필드는 단계1 기본값 고정(`ShapeJsonSynthesizer`로 채운 baseline에 enum override만 적용).

#### 2. `ExternalStubSynthesizer` 확장 (단계1 단일 등록 지점)

```java
boolean register(String method, String pathLiteral, BodyShape shape);                    // 단계1 (전역)
boolean registerVariant(String method, String pathLiteral, JsonNode body, String traceId); // 단계2 (격리)
void removeVariant(String method, String pathLiteral, String traceId);                    // none 모드 교체용
```

- `registerVariant`: 변형 body를 `(method, path, withHeader(trace-id 헤더, traceId))` 매칭으로 등록. 단계1 stub(헤더 매칭 없음)보다 **우선순위 높게**(WireMock priority) 두어 격리 요청에 변형이 매칭.
- otel/sleuth: trace-id 헤더명은 `TraceKey`가 제공(otel `traceparent` prefix 매칭 또는 `X-B3-TraceId`). 변형별 stub이 공존, 요청의 trace-id로 정확히 격리.
- none: trace-id 없음 → `removeVariant` + `registerVariant`(헤더 없이 전역)로 **순차 교체**(직렬).

#### 3. `TraceKey` 확장 — invoke 전 trace-id 노출

단계1은 outbound trace-id를 *읽기*만 했다. 단계2는 변형 stub을 invoke **전에** 그 invoke의 trace-id로 등록해야 하므로, 빌더가 발급하는 요청 trace-id를 **invoke 전에 알고 주입**해야 한다. 이는 이미 `SqlCaptureBackend.Scope`/`OtelSpanCapture.begin()`이 요청별 traceparent를 invoke 전에 발급·주입하므로(단계1 확인), 그 발급된 trace-id를 변형 등록에 사용한다. `TraceKey`에 inbound trace-id → outbound 매칭 헤더 조건 생성기를 추가:

```java
StubMatchCondition matchFor(String traceId);   // otel: traceparent가 trace-id 포함, sleuth: X-B3-TraceId==traceId
```

#### 4. 변형 탐색 루프 (`EndpointExplorationRunner`)

단계1 B2 루프(`run()`의 1차 explore 직후) **수렴 후**, enum 응답을 가진 호출 site에 대해 변형 루프를 돈다.

- 입력 fuzzer(`CoverageGuidedFuzzer`)는 **body 변이 전용**이라 직접 못 쓴다(설계상 별도 축). 변형 루프는 단계1 B2 루프와 같은 endpoint-scoped 패턴으로 신설하되 "변이 좌표=응답 변형".
- 각 변형마다: 변형 stub 등록 → endpoint invoke → `coverage.dump(true)` 기반 per-request 커버리지 → 직전 누적 대비 **새 분기 arm**이 열렸으면 보존.
- **budget**: `responseVariantBudget`(기본 = generator budget과 동일 cap). 소진 시 종료 + 로그.
- 보존된 변형 = 그 path의 추가 stub → 생성 테스트(provenance=SYNTHESIZED, 단계1 경로 재사용).

#### 5. provenance / 결정성 / loud-fail

- 변형 stub 캡처도 `responseProvenance=SYNTHESIZED`(단계1).
- 결정성: 변형 생성·측정 순서 결정적(필드/상수 정렬). 동일 commit → 동일 변형 집합·동일 stub·동일 커버리지.
- loud-fail: budget 절단(`response-variant-budget-truncated`), enum 필드 없는 path는 변형 0(정상, 단계1 단일 stub만).

### 격리 키 (단계1 `TraceKey` 재사용)

- **otel/sleuth**: 변형별 stub을 trace-id로 격리(공존 가능). 빌더가 invoke 전 발급한 trace-id를 변형에 바인딩.
- **none**: trace-id 없음 → 변형마다 순차 stub 교체(직렬). 동작은 하되 느리다.

### 결정성·budget 상세

- enum 필드 N개, 각 평균 M 상수 → M^N 변형. budget cap(32)으로 절단. 절단 시 어떤 조합을 우선? **각 enum의 모든 상수가 최소 1회 등장하도록 우선**(coverage 관점: 단일 필드 변형 우선 → 조합은 budget 여유 시). 절단량 loud 로그.

## 검증 SUT (갭 — fixture 확장)

`samples/order-service`의 `InventoryResponse(Integer available)`에 **enum 필드 추가** + `OrderController`가 그 enum으로 분기:

- `enum FulfillmentMode { STANDARD, EXPRESS_ONLY, BACKORDER }` (신규)
- `record InventoryResponse(Integer available, FulfillmentMode mode)` (확장)
- `OrderController.create`에 `switch (stock.mode())` 분기(각 상수 다른 arm/응답). **기존 `available < amount → 409` 분기는 보존**(단계1 E2E 회귀 없음).

이로써: 단계1은 `mode` 첫 상수 1 arm, **단계2는 STANDARD/EXPRESS_ONLY/BACKORDER 3 arm 모두 도달**.

## 테스트 전략 (double-loop TDD)

### E2E / 수용 (outer loop)

1. **enum 변형으로 모든 arm 도달** — order-service를 `--external-stubs` 없이 빌드 → `FulfillmentMode` 3개 상수가 각각 다른 분기(409/400/proceed)를 열어 **단계1 대비 추가 arm 커버리지** 달성.
2. **결정성** — 2회 빌드 동일 변형 집합·커버리지.
3. **budget 절단 loud** — enum 다수 fixture에서 budget 초과 시 `response-variant-budget-truncated` 기록.
4. **provenance** — 변형 stub 경유 캡처 SYNTHESIZED.
5. **단계1 회귀** — 단계1 E2E(`Stage1ExternalStubSynthesisE2E`) green 유지.

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

(3벤더 design-doc 리뷰 후 채움)
