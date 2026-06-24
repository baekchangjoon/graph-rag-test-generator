# 단계2-A — status-style 자유 String 리터럴 응답 변형 fuzzing (설계)

- 일자: 2026-06-24
- 브랜치: feat-stage2-string-literal-fuzzing
- 선행: 단계2(enum 상수 조합 응답 변형 fuzzing, PR #92 머지). 본 단계는 단계2가 만든 **변형 루프 인프라**(`ExternalStubSynthesizer.registerVariant`·trace-id 격리·커버리지 유도 재탐색·OR-병합·budget loud-truncation) 위에 **후보 출처만 교체**해 얹는다.
- 결정 경로: 사용자가 "단계2 진행해줘"로 단계2 후속을 지시. 두 후속 갈래(String 리터럴 / concolic 숫자) 중 **2-A(String 리터럴)부터**를 에이전트 권장(secretary 위임 체인 safe_default)으로 선택. 본 설계는 그 권장안이며 3벤더 design-doc 리뷰 1회 반영했다(아래 §리뷰 반영).

## 한 줄 요약

단계2는 **enum 타입** 응답 필드를 그 enum의 정적-완전 상수 집합으로 갈아끼워 모든 arm을 열었다. 단계2-A는 **String 타입** 응답 필드를, "**소비(SUT) 코드가 그 필드로 분기할 때 비교하는 문자열 리터럴**"을 후보 집합으로 삼아 같은 변형 루프로 갈아끼워, status-스타일 동치 분기(`if (resp.status().equals("APPROVED"))`)의 양 arm을 결정적(no-LLM·no-OpenAPI)으로 연다.

## 비목표 경계 (먼저 못박는다)

- **동치(equality) 비교 외 패턴** — phase-1은 **equals-family 동치 비교만**(아래 §컴포넌트 1). `switch (resp.f()) { case "X" }`·`Set.of("A","B").contains(resp.f())`·정적 `static final Set` contains는 **단계2-A 후속**(별도 작업). 근거: ConstraintExtractor가 equals 추출을 no-classpath에서 이미 입증했고, switch/contains AST 추출은 Spoon 버전 의존 prototype이 필요해(3벤더 공통 지적) phase-1 범위 폭발을 막는다.
- **체인 호출 receiver** — `"X".equals(client.check(t).region())` 같은 체인은 미지원(`ConstraintExtractor.fieldRef`가 단일 접근자만 인식). 직접 접근자(`resp.f()`)와 **단순 로컬 변수 바인딩**(`String r = resp.f(); ... r ...`)만.
- **concolic 숫자 경계 변형** — 응답 숫자 필드의 경계값(ASM+Z3)은 단계2-B(별도). 본 단계는 String 타입만.
- **enum 필드** — 단계2가 이미 처리. 본 단계는 String 필드만 추가(둘은 변형 루프 공유, 후보 출처만 다름).
- **컴파일타임 상수가 아닌 동적 비교값** — `resp.f().equals(someVar)`(변수), 외부 라이브러리 상수 참조 등 정적 확정 불가 비교값은 추출 안 함 + loud 로그(silent drop 금지).
- **LLM / OpenAPI 응답 합성** — 단계3.
- **중첩 객체 / 배열 응답 DTO 내부 String** — 단계1대로 `unsynthesizable-shape` loud-fail 유지. 평면 DTO 최상위 String 필드만.
- **병렬 탐색 실행 / attach 모드 변형 fuzzing** — 단계2 상속(직렬 유지, analysis E2E 우선; attach는 회귀 수준만).

## 전제 (Prerequisites) — 단계2에서 상속

- otel 모드 격리는 SUT OTEL agent가 outbound HTTP에 W3C `traceparent`를 전파하는 환경 전제. 미전파/`--trace-mode none`이면 변형 순차 교체로 동작.
- 응답 DTO FQN을 호출 site에서 못 뽑으면 `responseShape=empty`라 변형도 생성되지 않는다(정상, 단계1 loud-fail).
- 변형 대상 site는 B2가 stub을 등록한 site만(`stubSynthesizer.isRegistered`).

## 문제

단계1은 String 응답 필드를 형상-only 기본값으로 채운다. 그 값은 `ShapeJsonSynthesizer.scalarValue` 규칙상 **`"sample-<fieldName>"`**(예: `region` → `"sample-region"`; email 휴리스틱 등 일부 예외)이다. 그러면 그 필드 값으로 갈리는 SUT 분기는 **기본값이 떨어지는 한 arm만** 통과한다. 예(order-service 확장 검증 SUT, 실제 Java로 추가 예정):

```java
InventoryClient.InventoryResponse stock = inventory.check(request.type());  // {available:int, mode:FulfillmentMode, region:String}
if ("EMBARGOED".equals(stock.region())) {                                   // 단계1 기본값 "sample-region"은 false arm만
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");  // 미도달
}
```

enum과 달리 String은 상수 집합이 정적으로 완전하지 않다. 그러나 **소비 코드가 동치 분기에서 비교하는 리터럴("EMBARGOED")은 정적으로 추출 가능**하다. 그 리터럴을 변형 stub으로 주입하면 true arm이 열린다. false arm은 단계1 기본값(`"sample-region"`, 어떤 도메인 리터럴과도 같지 않음)이 이미 커버한다. 이것이 단계1 한계의 결정적·no-LLM 해소다.

## 설계

### 핵심 아이디어 — 후보 출처만 교체

단계2 변형 루프 데이터 흐름(이미 구현됨):

```
[변형 plan 생성] field → 후보값 목록  ──►  [exploreResponseVariants 루프]
                                            각 후보 V에 대해:
                                              registerVariant(method, path, body{field=V}, traceId)  // trace-id 격리
                                              delta = invoke(triggerInput)                            // 재탐색
                                              cumulativeCoverage OR= delta                            // 새 arm 보존
```

루프에서 **value-특정적인 부분은 "plan 생성"의 후보 출처뿐**이다. `applyEnumOverrides`(rename → `applyFieldOverrides`)는 `ObjectNode.put(field, value)`로 String을 그대로 주입한다 — enum 상수명도 결국 JSON String. 따라서 단계2-A는 **새 후보 출처 + 일반화된 plan 생성기**만 추가하고, 루프 본체·격리·OR-병합·provenance·budget·loud-truncation은 **변경 없이 재사용**한다.

### 컴포넌트

#### 1. `ResponseStringLiteralExtractor` (신규, `index` 패키지, Spoon)

- 입력: 공유 `SharedSpoonModel`(no-classpath), 응답 DTO의 평면 String 필드.
- 출력: `Map<String dtoFqn, Map<String fieldName, List<String> literals>>` — 결정적 정렬(dtoFqn·field·literal 모두 정렬·중복 제거). dtoFqn은 Spoon nested record 표기(`...InventoryClient$InventoryResponse` 형태)에 맞춰 `responseShape.javaType()`과 동일 키 규칙 사용.
- **기존 코드 재사용**: `ConstraintExtractor`의 `fieldRef`(단일 접근자 인식)·`stringLiteral`(CtLiteral<String> 추출) 헬퍼를 재사용/공유한다. `ConstraintExtractor.extractStringEqualities`는 **입력(handler) 측** equals를 전 클래스에서 뽑지만 본 추출기는 **응답 DTO 필드 접근자**로 필터링한다는 점만 다르다 → 헬퍼 공유, 필터만 신규.
- 추출 패턴(phase-1, equals-family 동치 비교):
  - `resp.f().equals("X")` / `"X".equals(resp.f())`
  - `resp.f().equalsIgnoreCase("X")` / `"X".equalsIgnoreCase(resp.f())` — 리터럴 자체가 분기를 켜는 결정적 후보이므로 포함(주입값=리터럴 원문). (Gemini I4 반영.)
  - `java.util.Objects.equals(resp.f(), "X")` / `Objects.equals("X", resp.f())` (Gemini I2 반영.)
  - **로컬 변수 바인딩**: `String r = resp.f();` 직후 같은 메서드 내 `r`에 대한 위 동치 비교(intra-procedural, 단순 직접 바인딩만). (Gemini I1·Cursor I7 반영.)
  - 비교 대상이 **동일 SUT 소스트리에 선언된 `static final String` 상수**면 그 필드의 `CtField.getDefaultExpression()`(CtLiteral<String>)에서 값 해석. **외부/미해석 상수(CtFieldRead, no-classpath)**는 `string-literal-const-unresolvable` loud 로그 후 skip. (Sonnet I4·Gemini I3 반영.)
- 접근자 ↔ DTO 필드 매핑: no-classpath에서 receiver 타입 해석은 신뢰 불가하므로 **메서드 simple-name(`f`/`getF`)을 1차 키**로 한다(`ConstraintExtractor.fieldRef`와 동일 전략, getType() 미의존). 같은 스코프에 동명 String 필드를 가진 DTO가 2개 이상이면 `string-literal-accessor-ambiguous` loud 로그 후 그 필드 skip. 오탐 변형은 budget-bounded이고 헛돌 뿐이며 loud 기록된다. (Sonnet I2 반영.)
- 동치 외 비교(`startsWith`/`contains(부분문자열)`/변수 비교)는 `string-literal-nonequality-skipped` loud 로그.

#### 2. 변형 plan 생성기 일반화 — `ResponseFieldVariantGenerator`

단계2 `EnumResponseVariantGenerator`의 budget 우선순위(단일 필드 먼저 → 2-way 카르테시안 → 절단+loud)·결정적 label·정렬은 String에도 동일 필요. **통합안 채택**(별도 클래스 복제는 DRY 위배로 기각):

- `EnumResponseVariantGenerator` → `ResponseFieldVariantGenerator`로 rename. `generate`가 **이미 baseline을 제외한 필드별 후보 맵** `Map<String fieldName, List<String> candidates>`와 budget을 받도록 시그니처 일반화. 카르테시안·budget·label 코어는 1곳.
- **baseline 제외 계약(명시)**: generate에 넘기는 맵은 **non-baseline 후보만** 담는다.
  - enum 필드: 호출자가 선언순 첫 상수를 제외(단계2 현행 로직 유지).
  - String 필드: 호출자가 `ShapeJsonSynthesizer`로 합성한 단계1 기본값(`scalarValue(fieldName)`, 예 `"sample-region"`)을 제외. baseline 값은 `synthesizeBody`가 아니라 필드 단위 `scalarValue`로 직접 얻어 순환 의존 회피. (Sonnet I5·Cursor I1/I3 반영.)
- **결정성 회귀 가드**: 통합 후 enum-only 경로의 출력 순서·label은 단계2와 **byte-동일**해야 한다(단계2 E2E·산출물 회귀). 단위 테스트로 enum 입력 동일성 고정.
- **budget 보호**: 코어가 단일 필드 변형을 (필드명 정렬 × 값 정렬) **먼저** 전부 생성한 뒤 카르테시안을 붙이므로, String 카르테시안이 enum 단일 필드 변형을 밀어내지 않는다. 카르테시안 절단은 허용 + loud. 별도 type별 쿼터는 불필요(YAGNI). (Gemini I5 부분 반영 — 보호는 정렬로 보장, 쿼터 기각.)

#### 3. 변형 루프 통합 (`EndpointExplorationRunner`)

- `runEnumResponseVariantLoops` → `runResponseVariantLoops`. 각 변형 대상 site에서 **필드별 후보 맵**을 조립:
  - enum 필드: `responseShape.fields()` 중 javaType이 `enumConstants`로 해석되는 필드 → (상수 − 선언순 첫 상수).
  - String 필드: javaType이 `java.lang.String`이고 `stringLiteralsByDto[responseShape.javaType()][field]`에 리터럴이 있는 필드 → (리터럴 − `scalarValue(field)`). 리터럴 0건 String 필드는 후보 없음(변형 0, 정상). (Cursor I3 반영.)
  - 같은 필드에 enum·String 후보가 동시에 생기지 않음(타입이 둘 중 하나)을 전제.
- `exploreEnumResponseVariants`/`applyEnumOverrides` → `exploreResponseVariants`/`applyFieldOverrides`(동작 동일).
- **산출물 식별자**: 변형 path/http id 접두사는 중립 `responsevar`로 rename(현 `enumvar` 하드코딩). 단계2 E2E가 id 접두사로 단언하면 그 단언도 갱신(회귀 체크리스트). (Sonnet I8·Cursor I6 반영.)

#### 4. 인덱싱 배선 (Cursor I2 반영 — 누락 보강)

`stringLiteralsByDto`를 enumConstants와 **동일 패턴**으로 정적 인덱스에 싣는다:
- `BuilderCli.indexStatically`에서 `ResponseStringLiteralExtractor().extract(model)` 호출.
- `StaticIndex` record에 `stringLiteralsByDto` 필드 추가(compact 생성자 `null→Map.of()` 가드), `IndexCache` 직렬화(+ 기존 `StaticIndexSerdeTest`·`IndexCacheTest` 갱신). 스키마 변경이므로 `IndexCache.SCHEMA_VERSION` 2→3 bump(레거시 캐시 무효화).
- `BuilderCli.build`→explore 배선, `EndpointExplorationRunner` 신규 필드(또는 explore 인자)로 전달(생성자 enumConstants와 같은 자리).
- 마커 rename(`enumvar`→`responsevar`, `enum-response-variant`→`response-variant`)은 생성 제외 필터 `test-generator` `Generator.java`도 함께 갱신한다(현재 `enum-response-variant` 하드코딩). enum 산출물 byte-동일 대상은 **생성기 label·순서로 한정**하며 마커/id rename은 그 대상이 아니다(enum E2E는 status·branch 기준이라 무관).

### 데이터 흐름 (단계2 대비 delta만)

```
[인덱싱] ResponseDtoIndexer.extractCallSites → callSites(응답 BodyShape)
         EnumConstantExtractor → enumConstants (단계2)
         ResponseStringLiteralExtractor → stringLiteralsByDto (★단계2-A 신규, StaticIndex 경유)
[탐색]   B2 재탐색 수렴 후 runResponseVariantLoops:
           후보맵 = enum해석(상수−first) ∪ string해석(리터럴−scalarValue)
           plan = ResponseFieldVariantGenerator.generate(후보맵, budget)
           exploreResponseVariants(plan, ...) ── 단계2 루프 그대로 ──► cumulativeCoverage OR-병합
```

### 검증 SUT 확장 (order-service)

- `InventoryClient.InventoryResponse`에 `String region` 추가(record 컴포넌트).
- `OrderController.create`의 EXPRESS 블록 안, switch **앞**에 String 동치 분기 추가:
  ```java
  if ("EMBARGOED".equals(stock.region())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");
  }
  ```
- **기존 테스트/stub 동반 갱신(중요)**: `region` 추가로 기존 inventory stub body가 `{"available":N,"mode":"...","region":"..."}`가 되어야 한다. 갱신 대상:
  - `e2e/external-stubs/inventory-stock.json` jsonBody에 `region` 추가.
  - `Stage1ExternalStubSynthesisE2E`(수동 stub 비교 경로) — green 재확인.
  - `OrderExpressApiTest`(`/inventory/stock` stub) — green 재확인.
  - `BuilderIntegrationTest`의 `consumedFields` 단언(현 `available`,`mode`)을 region 소비 반영해 갱신.
  - **`Stage2EnumResponseFuzzingE2E`** — region 분기를 switch 앞에 넣으면 라인이 밀려 **`SWITCH_LINE`(현 55) 단언이 깨진다**. 상수 재측정·갱신 필수. enum 3-arm 단언은 mode 기준이라 region 추가 자체엔 무관하나 라인 시프트는 영향. (Sonnet I9·Cursor I9 반영.)
- **단위 테스트 픽스처**: graph-rag-builder 단위 테스트의 `src/test/resources/sample-src`에는 현재 `OrderController.java`만 있고 `InventoryClient.java`가 없다. `sample-src/io/graphrag/sample/orders/InventoryClient.java`를 **신규 생성**(nested `InventoryResponse` record, `region` 포함)하고 `OrderController`에 equals 분기를 추가해 `ResponseStringLiteralExtractor` 단위 테스트 픽스처로 쓴다. (Cursor I2/I8 반영.)

이로써: 단계1·2는 `region` 기본값으로 EMBARGOED arm **미도달**, 단계2-A는 "EMBARGOED" 리터럴 변형으로 **도달**.

## 수용 기준 (E2E — 요구사항명세에서 REQ-ID 부여)

요구사항명세(`docs/superpowers/requirements/2026-06-24-stage2-string-literal-response-fuzzing-requirements.md`)를 **구현 전 작성**하고, 아래를 REQ-ID로 매핑한다(단계2 enum 요구사항 문서 형식). E2E 테스트 클래스: **`Stage2AStringLiteralFuzzingE2E`**, 검증 anchor = 확장된 `OrderController`의 EMBARGOED arm(422 throw) 라인(별도 상수).

1. **String 리터럴 변형으로 새 arm 도달** — order-service를 단계2-A 빌더로 `--external-stubs` 없이 탐색 → `region` 동치 분기 리터럴("EMBARGOED") 추출·변형 stub 등록·재invoke → POST `/api/orders` 커버리지에 EMBARGOED arm covered, 단계2(enum만) 대비 arm 증가.
2. **결정성** — 2회 빌드 산출물(그래프 JSON·변형 label) byte-동일.
3. **비동치/미해석 loud skip** — `startsWith`·변수 비교·외부 상수는 추출 제외 + 해당 loud 로그(silent cap 금지).
4. **변형 provenance** — String 변형 stub 경유 캡처도 `SYNTHESIZED`.
5. **단계1·단계2 회귀 없음** — 기존 E2E(enum 3-arm 포함) 전부 green, enum 변형 label byte-동일.
6. **budget 절단 loud** — 후보 폭증 시 절단 + loud 로그.

## 완료 정의

- 단계2-A E2E green + unit green + 요구사항명세 REQ 매트릭스 100%(Must + 미연기 Should).
- 전체 회귀(graph-rag-builder + shared-model + order-service) green, **단계2 enum 변형 label·산출물 byte-동일**(통합 생성기 회귀 가드).
- 3벤더 design-doc 리뷰 반영(완료) → spec-compliance + code-quality 리뷰 통과.

## 고려한 대안 (요약)

- **CoverageGuidedFuzzer에 응답-String 변형 축 추가**: fuzzer 추상화 대수술 → 범위 폭발. 별도 변형 루프 재사용이 외과적. **기각**.
- **DTO 필드의 모든 가능한 문자열을 후보로**: String은 무한 → 불가능. 소비 분기 리터럴로 한정이 핵심. **채택**.
- **별도 String 변형 생성기 클래스**: DRY 위배. **기각**(통합).

## 리뷰 반영 (3벤더 design-doc 리뷰, 1회)

세 리뷰어(Claude Sonnet / Gemini 3.5 Flash / Cursor auto) 전원 `approved_with_conditions`. 판정:

- **반영(important)**: 단계1 String 기본값 `"sample-<fieldName>"` 정정(전원 I1) / Spoon no-classpath 상수 해석 한계·동일 소스트리 한정+loud skip(Sonnet I4·Gemini I3) / **switch·Set.of().contains() phase-1 제외(equals-family로 범위 축소)**(Sonnet I3) / `ConstraintExtractor` 헬퍼 재사용(Sonnet I7·Cursor I5) / 통합 생성기 baseline 제외 계약 명시(Sonnet I5·Cursor I1/I3) / 인덱싱 배선(StaticIndex/IndexCache/BuilderCli) 보강(Cursor I2) / E2E 클래스명·REQ-ID·요구사항명세 선행(Sonnet I6·Cursor I4) / `SWITCH_LINE` 재측정(Sonnet I9·Cursor I9) / Objects.equals·equalsIgnoreCase·로컬 변수 바인딩 추가(Gemini I1/I2/I4·Cursor I7) / 산출물 id `responsevar` rename(Sonnet I8·Cursor I6) / sample-src 단위 픽스처(Cursor I8).
- **부분 반영**: budget 쿼터(Gemini I5) — single-field-first 정렬이 단일 필드 변형을 이미 보호함을 명시하고 type별 쿼터는 **기각**(YAGNI).
- **기각**: 별도 String 생성기 클래스(중복) — 통합으로 대체. 체인 호출 receiver(Cursor I7 일부) — phase-1 비목표로 명시.

## 한계 (단계2-A 후속·단계3)

- equals-family 동치·직접/로컬바인딩 접근자·동일 소스트리 상수만. switch/case·`Set.of().contains()`·정적 Set·체인 호출·동적 비교값·`equalsIgnoreCase`의 케이스 열거는 후속.
- 숫자 경계(concolic)는 단계2-B.
- 중첩/배열 내부 String은 단계1 loud-fail 유지.
- LLM/OpenAPI는 단계3.
