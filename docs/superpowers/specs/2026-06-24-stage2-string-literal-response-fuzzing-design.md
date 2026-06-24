# 단계2-A — status-style 자유 String 리터럴 응답 변형 fuzzing (설계)

- 일자: 2026-06-24
- 브랜치: feat-stage2-string-literal-fuzzing
- 선행: 단계2(enum 상수 조합 응답 변형 fuzzing, PR #92 머지). 본 단계는 단계2가 만든 **변형 루프 인프라**(`ExternalStubSynthesizer.registerVariant`·trace-id 격리·커버리지 유도 재탐색·OR-병합·budget loud-truncation) 위에 **후보 출처만 교체**해 얹는다.
- 결정 경로: 사용자가 "단계2 진행해줘"로 단계2 후속을 지시했고, 두 후속 갈래(String 리터럴 / concolic 숫자) 중 **2-A(String 리터럴)부터**를 에이전트 권장(safe_default)으로 선택. 본 설계는 그 권장안이며 3벤더 design-doc 리뷰로 검증한다.

## 한 줄 요약

단계2는 **enum 타입** 응답 필드를 그 enum의 정적-완전 상수 집합으로 갈아끼워 모든 arm을 열었다. 단계2-A는 **String 타입** 응답 필드를, "**소비(SUT) 코드가 그 필드로 분기할 때 비교하는 문자열 리터럴**"을 후보 집합으로 삼아 같은 변형 루프로 갈아끼워, status-스타일 분기(`if (resp.status().equals("APPROVED"))`)의 양 arm을 결정적(no-LLM·no-OpenAPI)으로 연다.

## 비목표 경계 (먼저 못박는다)

- **concolic 숫자 경계 변형** — 응답 숫자 필드의 경계값(ASM+Z3)은 단계2-B(별도 작업). 본 단계는 String 타입 필드만.
- **enum 필드** — 단계2가 이미 처리. 본 단계는 enum이 **아닌** String 필드만 추가로 다룬다(둘은 변형 루프를 공유하되 후보 출처가 다르다).
- **컴파일타임 상수가 아닌 동적 비교값** — `resp.status().equals(someVariable)`, `equalsIgnoreCase`, 정규식/`startsWith`/`contains(부분문자열)` 등 비-동치 비교는 후보를 정적으로 확정할 수 없으므로 **대상 외**(추출 안 함, loud 로그). 동치 비교(`.equals(리터럴)`, `리터럴.equals(...)`, `switch/case "리터럴"`, `Set.of("A","B").contains(필드)`)와 **컴파일타임 String 상수 참조**(`static final String X = "..."`)만 추출한다.
- **LLM / OpenAPI 응답 합성** — 단계3.
- **중첩 객체 / 배열 응답 DTO 내부의 String** — 단계1대로 `unsynthesizable-shape` loud-fail 유지. 본 단계는 단계1이 합성 가능한 **평면 DTO의 최상위 String 필드**만.
- **병렬 탐색 실행** — 직렬 유지. 격리는 단계2의 trace-id 메커니즘 그대로.

## 전제 (Prerequisites) — 단계2에서 상속

- otel 모드 격리는 SUT OTEL agent가 outbound HTTP에 W3C `traceparent`를 전파하는 환경 전제. 미전파/`--trace-mode none`이면 none 모드와 동일하게 **변형 순차 교체**로 동작(단계2와 동일).
- 응답 DTO FQN을 호출 site에서 못 뽑으면(`ResponseDtoIndexer` 미지원 패턴) `responseShape=empty`라 변형도 생성되지 않는다(정상, 단계1 loud-fail 기록).
- 변형 대상 site는 B2가 stub을 등록한 site만(`stubSynthesizer.isRegistered`). 미등록 site는 변형 대상 아님.

## 문제

단계1은 String 응답 필드를 형상-only 기본값(예: `"string"`)으로 채운다. 그러면 그 필드 값으로 갈리는 SUT 분기는 **기본값이 떨어지는 한 arm만** 통과한다. 예(order-service 확장 검증 SUT, 실제 Java로 추가 예정):

```java
InventoryClient.InventoryResponse stock = inventory.check(request.type());  // {available:int, mode:FulfillmentMode, region:String}
if ("EMBARGOED".equals(stock.region())) {                                   // 단계1 기본값("string")은 false arm만
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");  // 미도달
}
```

enum과 달리 String은 상수 집합이 정적으로 완전하지 않다. 그러나 **소비 코드가 분기에서 비교하는 리터럴 집합("EMBARGOED")은 정적으로 추출 가능**하다. 그 리터럴을 변형 stub으로 주입하면 true arm이 열린다. false arm은 단계1 기본값(어떤 리터럴과도 같지 않음)이 이미 커버한다. 이것이 단계1 한계의 결정적·no-LLM 해소다.

## 설계

### 핵심 아이디어 — 후보 출처만 교체

단계2 변형 루프의 데이터 흐름은 다음과 같다(이미 구현됨):

```
[변형 plan 생성] field → 후보값 목록  ──►  [exploreResponseVariants 루프]
                                            각 후보 V에 대해:
                                              registerVariant(method, path, body{field=V}, traceId)  // trace-id 격리
                                              delta = invoke(triggerInput)                            // 재탐색
                                              cumulativeCoverage OR= delta                            // 새 arm 보존
```

이 루프에서 **value-특정적인 부분은 "plan 생성"의 후보 출처뿐**이다. `applyFieldOverrides`(현 `applyEnumOverrides`)는 `ObjectNode.put(field, value)`로 이미 String을 그대로 주입한다 — enum 상수명도 결국 JSON String이다. 따라서 단계2-A는 **새 후보 출처(소비 코드 분기 리터럴)와 그것을 받는 일반화된 plan 생성기**만 추가하고, 루프 본체·격리·OR-병합·provenance·budget·loud-truncation은 **변경 없이 재사용**한다.

### 컴포넌트

#### 1. `ResponseStringLiteralExtractor` (신규, `index` 패키지, Spoon)

- 입력: SUT `CtModel`(이미 공유 `SharedSpoonModel`), 응답 DTO 목록(`ExternalCallSite.responseShape()`의 평면 String 필드).
- 출력: `Map<String dtoFqn, Map<String fieldName, List<String> literals>>` — 결정적 정렬(dtoFqn·field·literal 모두 정렬, 중복 제거).
- 알고리즘: 응답 DTO의 String 필드 `f`마다, SUT 코드에서 그 필드 **접근자 호출**(record `f()` 또는 bean `getF()`)을 피연산자로 가지는 동치 비교를 찾아 리터럴을 수집:
  - `resp.f().equals("X")` / `"X".equals(resp.f())`
  - `switch (resp.f()) { case "X": ... }`
  - `Set.of("A","B").contains(resp.f())` / `List.of(...).contains(...)`
  - 비교 대상이 **컴파일타임 String 상수**(`static final String`)면 그 상수값으로 해석.
- 동치가 아닌 비교(`equalsIgnoreCase`, `startsWith`, `contains(부분문자열)`, 변수 비교)는 수집하지 않고 `string-literal-nonequality-skipped` loud 로그(silent drop 금지).
- 접근자 ↔ DTO 필드 매핑: 접근자 호출의 수신 표현식 타입(no-classpath 모드에서 가능한 한)과 메서드 simple-name(`f`/`getF`)으로 매칭. 타입 해석 실패 시 simple-name 폴백(단계2 enum FQN/simple-name 폴백 규칙과 동일 — 같은 DTO 내 동명 필드 오탐 가능성은 budget·결정성에 영향 없음, 변형이 헛돌 뿐이며 loud 기록).

#### 2. 변형 plan 생성기 일반화

단계2 `EnumResponseVariantGenerator.generate(BodyShape, Map<enumFqn,consts>, budget)`의 budget 우선순위(단일 필드 ΣM 먼저 → 2-way 카르테시안 → 절단+loud)·결정적 label·baseline 제외 로직은 **String에도 동일하게 필요**하다. 두 안:

- **(권장) 후보 해석을 호출자로 끌어올린 통합 생성기.** `generate`가 `Map<fieldName, List<candidateValues>>`(이미 해석된 필드별 후보)와 baseline 맵을 받도록 시그니처를 일반화하고, enum/String 해석(타입→상수 / 필드→리터럴)은 각 호출 경로가 수행한다. 카르테시안·budget·label 코어는 1곳. enum 경로는 baseline=선언순 첫 상수, String 경로는 baseline=단계1 기본 String(변형 목록에서 제외). 클래스명은 `ResponseFieldVariantGenerator`로 rename(엄밀히는 enum 전용이 아니므로).
- (대안) `StringLiteralResponseVariantGenerator` 별도 클래스로 코어 복제. 단순하지만 카르테시안·budget·label 로직 2벌 → 표류 위험. **기각**(DRY).

> 결정성: 통합 후에도 enum 경로의 출력 순서·label은 단계2와 byte-동일해야 한다(단계2 회귀 가드). String 경로는 field 정렬 × literal 정렬.

#### 3. 변형 루프 통합 (`EndpointExplorationRunner`)

- `runEnumResponseVariantLoops` → `runResponseVariantLoops`로 일반화. 각 변형 대상 site에 대해:
  - enum 후보(단계2: `enumConstants`에서 타입별 해석) **와** String 후보(단계2-A: `stringLiteralsByDto`에서 필드별 해석)를 **합쳐** 필드별 후보 맵을 만든다. 한 site의 한 호출에서 enum 필드와 String 필드가 함께 변형될 수 있다(2-way 카르테시안이 enum×String도 자연스럽게 포함 — budget 내).
  - 나머지(baselineResponse 합성, triggerInput 선택, `exploreResponseVariants`, 새 arm 보존, SYNTHESIZED CapturedHttpCall 기록, cumulativeCoverage 집계 path)는 단계2 그대로.
- `exploreEnumResponseVariants`/`applyEnumOverrides` → `exploreResponseVariants`/`applyFieldOverrides` rename(동작 동일).

### 데이터 흐름 (단계2 대비 delta만)

```
[인덱싱] ResponseDtoIndexer.extractCallSites → callSites(응답 BodyShape)
         EnumConstantExtractor → enumConstants (단계2)
         ResponseStringLiteralExtractor → stringLiteralsByDto (★단계2-A 신규)
[탐색]   B2 재탐색 수렴 후 runResponseVariantLoops:
           plan = ResponseFieldVariantGenerator.generate(필드별 후보 = enum해석 ∪ string해석, budget)
           exploreResponseVariants(plan, ...) ── 단계2 루프 그대로 ──► cumulativeCoverage OR-병합
```

### 검증 SUT 확장 (order-service)

- `InventoryClient.InventoryResponse`에 `String region` 추가(record 컴포넌트).
- `OrderController.create`의 EXPRESS 블록 안, switch **앞**에 String 분기 추가:
  ```java
  if ("EMBARGOED".equals(stock.region())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");
  }
  ```
- **기존 테스트/stub 동반 갱신(중요)**: `region` 추가로 기존 inventory stub body가 `{"available":N,"mode":"...","region":"..."}`가 되어야 역직렬화가 안전하다(누락 시 region=null → `.equals` false라 NPE는 없지만, 단계1 E2E의 stub 비교 경로를 위해 명시값 부여). 갱신 대상: `Stage1ExternalStubSynthesisE2E`(수동 stub 비교), `OrderExpressApiTest`(`/inventory/stock` stub), `Stage2EnumResponseFuzzingE2E`(있으면). 단계1·단계2 E2E green 재확인.

이로써: 단계1·2는 `region` 기본값으로 EMBARGOED arm **미도달**, 단계2-A는 "EMBARGOED" 리터럴 변형으로 **도달**.

## 수용 기준 (E2E 후보 — 요구사항명세에서 REQ-ID 부여 예정)

1. **String 리터럴 변형으로 새 arm 도달** — Given order-service를 단계2-A 빌더로 `--external-stubs` 없이 탐색, When `region` String 필드의 소비 분기 리터럴("EMBARGOED")을 추출해 변형 stub 등록·재invoke, Then POST `/api/orders` 결과 커버리지에 EMBARGOED arm(422 throw)이 covered가 되고, 단계2(enum만) 대비 arm 수가 증가한다.
2. **결정성** — 2회 빌드 산출물(그래프 JSON·변형 label) byte-동일.
3. **비동치 비교 loud skip** — `equalsIgnoreCase`/변수 비교 분기는 추출 제외 + `string-literal-nonequality-skipped` 로그(silent cap 금지).
4. **변형 provenance** — String 변형 stub 경유 캡처도 `SYNTHESIZED`.
5. **단계1·단계2 회귀 없음** — 기존 E2E(enum 3-arm 포함) 전부 green 유지, enum 변형 label byte-동일.
6. **budget 절단 loud** — 후보 폭증 시 절단 + loud 로그.

## 완료 정의

- 단계2-A E2E green + unit green + 요구사항명세 REQ 매트릭스 100%(Must + 미연기 Should).
- 전체 회귀(graph-rag-builder + shared-model + order-service) green, **단계2 enum 변형 label·산출물 byte-동일**(통합 생성기 회귀 가드).
- 3벤더 design-doc 리뷰 반영 → spec-compliance + code-quality 리뷰 통과.

## 고려한 대안 (요약)

- **CoverageGuidedFuzzer에 응답-String 변형 축 추가**: 가장 통합적이나 fuzzer 추상화 대수술 → 범위 폭발. 단계2가 택한 별도 변형 루프 재사용이 외과적. **기각**.
- **변형 후보를 enum처럼 "DTO 필드의 모든 가능한 문자열"로 잡기**: String은 무한 → 불가능. 소비 분기 리터럴로 한정하는 것이 본 설계의 핵심. **채택**.
- **별도 String 변형 생성기 클래스**: §컴포넌트 2에서 DRY로 기각.

## 한계 (단계2-A 후속·단계3)

- 동치 비교·컴파일타임 상수만. 동적 비교값·`equalsIgnoreCase`·부분 매칭은 미지원(loud skip).
- 숫자 경계(concolic)는 단계2-B.
- 중첩/배열 내부 String은 단계1 loud-fail 유지.
- LLM/OpenAPI는 단계3.
