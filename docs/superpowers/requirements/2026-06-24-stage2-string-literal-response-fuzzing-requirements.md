# 단계2-A status-style String 리터럴 응답 변형 fuzzing 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-stage2-string-literal-response-fuzzing-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green) + 단계1·단계2 회귀 green (enum 변형 label·순서 byte-동일)

## 요구사항 목록

### REQ-001 — String 리터럴 변형으로 새 arm 도달
- 유형: Functional / 우선순위: Must
- 설명: 응답 DTO의 String 필드를, 소비(SUT) 코드가 그 필드로 동치 분기할 때 비교하는 리터럴로 갈아끼운 변형 stub을 등록·재invoke해, 그 String으로 갈리는 SUT 분기의 true arm을 도달한다. false arm은 단계1 기본값이 이미 커버.
- 수용기준:
  - Given order-service를 단계2-A 빌더로 `--external-stubs` 없이 탐색(`InventoryResponse.region: String`, `OrderController`에 `if ("EMBARGOED".equals(stock.region()))` 422 분기), When "EMBARGOED" 리터럴을 변형 stub으로 등록·재invoke, Then POST `/api/orders` 커버리지에 EMBARGOED arm(422 throw)이 covered이고, 단계2(enum만) 대비 arm 수가 증가한다.
- 검증 레벨: E2E black-box

### REQ-002 — 결정성
- 유형: Non-functional / 우선순위: Must
- 설명: String 리터럴 추출·변형 생성·측정 순서가 결정적이라 동일 commit 2회 실행이 동일 결과.
- 수용기준:
  - Given 동일 commit 2회 빌드, When 변형 label 목록·`ExploredPath` id 집합·JaCoCo branch 집합을 비교, Then 셋 다 동일하다.
- 검증 레벨: E2E black-box

### REQ-003 — 비동치/미해석 비교 loud skip (silent drop 금지)
- 유형: Functional / 우선순위: Must
- 설명: 동치(equals-family) 외 비교(`startsWith`/`contains` 부분문자열/변수 비교)와 미해석 상수(외부/no-classpath `CtFieldRead`), 접근자 모호(아래 정의)는 후보로 추출하지 않고 각각 loud 로그를 남긴다. 모호 판정은 **응답 callSite의 `responseShape`에 실존하는 String 필드와 교차**한 뒤, 같은 simple-name String 필드를 가진 응답 DTO가 2개 이상일 때만 발동(흔한 필드명의 과한 skip 방지; over-fuzzing 무한 후보는 budget·결정성 위험으로 비채택).
- 수용기준:
  - Given `startsWith`/변수 비교 분기, When 추출, Then 후보 미생성 + `string-literal-nonequality-skipped` 로그.
  - Given 외부/미해석 `static final String` 참조 비교, When 추출, Then 후보 미생성 + `string-literal-const-unresolvable` 로그.
  - Given 같은 simple-name String 필드를 가진 응답 DTO 2개 이상, When 추출, Then 그 필드 skip + `string-literal-accessor-ambiguous` 로그.
- 검증 레벨: integration (unit)

### REQ-004 — 변형 provenance 태깅
- 유형: Functional / 우선순위: Must
- 설명: String 변형 stub 경유 캡처도 `responseProvenance=SYNTHESIZED`로 판정된다(전역 미등록이어도). 단계2 메커니즘 재사용.
- 수용기준:
  - Given String 변형 stub(헤더 매칭, 전역 Set 미등록)으로 통과한 외부 호출, When 캡처, Then `responseProvenance==SYNTHESIZED`.
- 검증 레벨: E2E black-box

### REQ-005 — 단계1·단계2 회귀 없음 (enum 생성기 byte-동일)
- 유형: Non-functional / 우선순위: Must
- 설명: SUT fixture 확장(region 추가)·생성기 통합(`ResponseFieldVariantGenerator`) 후에도 단계1·단계2 E2E가 green을 유지하고, **`ResponseFieldVariantGenerator`의 enum-only 입력 출력(변형 label·순서)이 단계2 `EnumResponseVariantGenerator`와 byte-동일**하다. (산출물 id/discoveredBy 마커의 `enumvar`→`responsevar` rename은 byte-동일 대상이 아님 — REQ-009 참조. enum E2E는 status·branch 집합 기준이라 마커 rename에 무관함을 확인.)
- 수용기준:
  - Given region stub 갱신·생성기 통합·마커 rename, When `Stage1ExternalStubSynthesisE2E`·`Stage2EnumResponseFuzzingE2E` 실행, Then 전부 green.
  - Given 동일 enum 입력(단계2 테스트 케이스), When `ResponseFieldVariantGenerator.generate`, Then 출력 label·순서가 단계2 `EnumResponseVariantGenerator`와 동일하다.
- 검증 레벨: E2E black-box + integration (unit)

### REQ-006 — budget 절단 loud (silent cap 금지)
- 유형: Functional / 우선순위: Must
- 설명: enum+String 합친 변형 수가 budget을 초과하면 잘라내되 절단량을 loud 로그로 기록한다. 단일 필드 변형은 카르테시안보다 먼저 채워 절단에서 보호.
- 수용기준:
  - Given budget=2 + 변형 3개, When generate, Then `response-variant-budget-truncated ... kept=2 dropped=1`가 기록되고 kept=2다.
  - Given enum 1필드 + String 1필드, When generate, Then 두 단일 필드 변형이 2-way 조합보다 먼저 채워진다.
- 검증 레벨: integration (unit)

### REQ-007 — ResponseStringLiteralExtractor (equals-family 추출)
- 유형: Functional / 우선순위: Must
- 설명: SUT `CtModel`(no-classpath)에서 응답 DTO String 필드 접근자(simple-name 키)에 대한 equals-family 동치 비교 리터럴을 결정적으로 수집한다. 출력 `Map<dtoFqn, Map<field, List<literal>>>` 정렬·중복제거. **dtoFqn 매핑 알고리즘**: 추출은 simple-name(`f`/`getF`) 키로 리터럴을 모으되, `ResponseDtoIndexer.extractCallSites`가 준 각 callSite `responseShape`의 String 필드 집합과 교차해, simple-name이 그 shape의 필드일 때만 `dtoFqn=responseShape.javaType()`(Spoon nested 표기 `...$InventoryResponse`) 버킷에 넣는다. **헬퍼 공유**: `ConstraintExtractor.fieldRef`/`stringLiteral`은 현재 private이므로, `io.graphrag.builder.index`의 공유 유틸(예 `SpoonExpressionRefs`)로 추출하거나 package-private로 승격해 `ResponseStringLiteralExtractor`가 코드 복제 없이 경유한다. `ConstraintExtractor.extractStringEqualities`는 현재 `.equals()`만 처리 → Objects.equals/equalsIgnoreCase는 신규.
- 수용기준:
  - Given `resp.f().equals("X")` / `"X".equals(resp.f())`, When extract, Then field f → ["X"].
  - Given `resp.f().equalsIgnoreCase("X")` / `"X".equalsIgnoreCase(resp.f())`, When extract, Then field f → ["X"](리터럴 원문만; 대소문자 변형 열거는 후속).
  - Given `Objects.equals(resp.f(), "X")` **및** `Objects.equals("X", resp.f())`(양방향), When extract, Then field f → ["X"].
  - Given `String r = resp.f(); ... "X".equals(r)`(단순 로컬 바인딩), When extract, Then field f → ["X"].
  - Given 동일 소스트리 `static final String C = "X"; ... C.equals(resp.f())`, When extract, Then field f → ["X"].
  - Given 추출 결과, When 같은 입력 2회, Then dtoFqn·field·literal 정렬 동일(결정적).
- 검증 레벨: integration (unit)

### REQ-008 — 변형 plan 생성기 통합 (ResponseFieldVariantGenerator)
- 유형: Functional / 우선순위: Must
- 설명: `EnumResponseVariantGenerator` → `ResponseFieldVariantGenerator` rename. `generate(Map<field,List<candidate>> nonBaselineCandidates, budget)`로 일반화 — baseline 제외는 호출자 책임(enum=선언순 첫 상수, String=`scalarValue`). 카르테시안·budget·결정적 label 코어 1곳.
- 수용기준:
  - Given non-baseline 후보 맵(단일 필드 2값), When generate(budget≥2), Then 2변형 label 결정적.
  - Given enum 1필드 + String 1필드 후보, When generate, Then 단일 필드 변형이 먼저, enum×String 2-way가 후순.
- 검증 레벨: integration (unit)

### REQ-009 — 변형 루프 통합 + 후보맵 조립 + 마커 rename (runResponseVariantLoops)
- 유형: Functional / 우선순위: Must
- 설명: `runEnumResponseVariantLoops` → `runResponseVariantLoops`. 각 site에서 enum 후보(상수−first)와 String 후보(`stringLiteralsByDto[shape.javaType()][field]` − `shapes.scalarValue(field.javaType(), List.of(), field.name())`의 텍스트값)를 필드별로 합쳐 plan 생성. 리터럴 0건 String 필드는 후보 없음(변형 0). **마커 rename**: 변형 path/http id 접두사 `enumvar`→`responsevar`, `discoveredBy` `enum-response-variant`→`response-variant` (`EndpointExplorationRunner` + 생성 제외 필터 `Generator.java:81`을 함께 갱신).
- 수용기준:
  - Given enum+String 필드를 가진 응답 path, When 루프, Then enum·String 변형이 같은 cumulativeCoverage에 OR-병합되고 각 새 arm 보존.
  - Given 리터럴 0건 String 필드, When 후보맵 조립, Then 그 필드 변형 0(정상).
  - Given 마커 rename, When `Generator` 렌더링, Then `response-variant` discoveredBy path가 생성에서 제외된다(기존 enum 동작 보존).
- 검증 레벨: integration

### REQ-010 — 인덱싱 배선 (StaticIndex 경유 전달 + 캐시 호환)
- 유형: Functional / 우선순위: Must
- 설명: `ResponseStringLiteralExtractor` 산출 `stringLiteralsByDto`를 enumConstants와 동일 패턴으로 `StaticIndex`에 싣고 `IndexCache` 직렬화 + `BuilderCli`→explore 배선 + `EndpointExplorationRunner`로 전달한다. `StaticIndex` compact 생성자에 `null→Map.of()` 가드. 스키마 변경이므로 `IndexCache.SCHEMA_VERSION` 2→3로 bump(레거시 캐시 무효화).
- 수용기준:
  - Given 빌드, When indexStatically→explore, Then `EndpointExplorationRunner`가 `stringLiteralsByDto`를 수신한다.
  - Given 직렬화 round-trip, When `StaticIndex` 저장·로드(`IndexCache`), Then `stringLiteralsByDto` 보존.
  - Given SCHEMA_VERSION 2 레거시 캐시, When 로드, Then 캐시 무효화(재인덱싱)된다.
- 검증 레벨: integration

### REQ-011 — SUT fixture 확장 + 기존 stub/단언 동반 갱신
- 유형: Functional / 우선순위: Must
- 설명: order-service `InventoryClient.InventoryResponse`에 `String region` 추가 + `OrderController`의 EXPRESS 블록 switch **앞**에 `if ("EMBARGOED".equals(stock.region()))` 422 분기. region 추가·분기 삽입으로 깨지는 모든 기존 stub·단언·픽스처를 동반 갱신:
  - `e2e/external-stubs/inventory-stock.json` jsonBody에 `region` 추가.
  - `Stage1ExternalStubSynthesisE2E`(수동 stub 비교) green 재확인.
  - `OrderExpressApiTest`(`/inventory/stock` stub) green 재확인.
  - `Stage2EnumResponseFuzzingE2E` `SWITCH_LINE` 상수 재측정(라인 시프트).
  - `BuilderIntegrationTest`의 `consumedFields` 단언(현 `containsExactlyInAnyOrder("available","mode")`)을 region 소비 반영해 갱신.
  - graph-rag-builder 단위 픽스처: `sample-src/io/graphrag/sample/orders/InventoryClient.java` **신규 생성**(nested `InventoryResponse` record, `region` 포함) + `OrderController`에 equals 분기 추가(현 sample-src엔 InventoryClient 없음).
- 수용기준:
  - Given region 추가·stub/픽스처 갱신, When order-service 빌드·`OrderExpressApiTest`·`Stage1ExternalStubSynthesisE2E`·`BuilderIntegrationTest` 실행, Then 역직렬화 NPE 없이 green.
  - Given region 분기 삽입 라인 시프트, When `Stage2EnumResponseFuzzingE2E` 실행, Then `SWITCH_LINE` 갱신 후 enum 3-arm 단언 green.
  - Given sample-src InventoryClient 신규·OrderController equals 분기, When `ResponseStringLiteralExtractorTest` 실행, Then region→["EMBARGOED"] 추출 green.
- 검증 레벨: E2E + integration

### REQ-012 — none 모드 String 변형 순차 교체
- 유형: Non-functional / 우선순위: Should
- 설명: `--trace-mode none`(traceparent 미전파)에서 trace-id 없이 String 변형마다 순차 stub 교체(전역 stub 보존, 변형만 등록·제거)로 동작한다. 단계2 enum none-mode(구 REQ-010) 메커니즘을 통합 루프에서 String에도 적용.
- 수용기준:
  - Given none 모드 + String 변형, When 통합 변형 루프, Then 전역 stub 삭제 없이 변형 순차 교체로 String arm 도달.
- 검증 레벨: integration

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | String 변형 새 arm 도달 | `Stage2AStringLiteralFuzzingE2E#stringVariantReachesEmbargoedArm` | E2E | 🔴 planned |
| REQ-002 | 결정성 | `Stage2AStringLiteralFuzzingE2E#deterministicAcrossRuns` | E2E | 🔴 planned |
| REQ-003 | 비동치/미해석 loud skip | `ResponseStringLiteralExtractorTest#loudSkips` | unit | 🔴 planned |
| REQ-004 | 변형 provenance | `Stage2AStringLiteralFuzzingE2E#variantStubCapturesAreSynthesized` | E2E | 🔴 planned |
| REQ-005 | 단계1·2 회귀 (enum byte-동일) | `Stage1ExternalStubSynthesisE2E` + `Stage2EnumResponseFuzzingE2E` + `ResponseFieldVariantGeneratorTest#enumPathByteIdenticalToStage2` | E2E+unit | 🔴 planned |
| REQ-006 | budget 절단 loud | `ResponseFieldVariantGeneratorTest#budgetTruncationLoud` | unit | 🔴 planned |
| REQ-007 | ResponseStringLiteralExtractor | `ResponseStringLiteralExtractorTest` | unit | 🔴 planned |
| REQ-008 | 생성기 통합 | `ResponseFieldVariantGeneratorTest` | unit | 🔴 planned |
| REQ-009 | 변형 루프 통합·후보맵·마커 rename | `StringLiteralVariantReExploreTest` + `GeneratorVariantExclusionTest`(갱신) | integration | 🔴 planned |
| REQ-010 | 인덱싱 배선·캐시 호환 | `StaticIndexSerdeTest`(갱신) + `IndexCacheTest`(갱신) + `IndexCacheWiringTest` | integration | 🔴 planned |
| REQ-011 | SUT fixture + stub/단언 갱신 | `OrderExpressApiTest`·`Stage1ExternalStubSynthesisE2E`·`Stage2EnumResponseFuzzingE2E`·`BuilderIntegrationTest`(갱신) | E2E+integration | 🔴 planned |
| REQ-012 | none 모드 String 순차 교체 | `StringLiteralVariantNoneModeTest` | integration | 🔴 planned |

Coverage: 0/12 green (0%) — target 12/12 (100%). 대상 분모=12 전체(REQ-001~011 Must[002·005는 Non-functional Must] + REQ-012 미연기 Should). Won't/deferred 없음.

## 단계 경계 (이 명세에서 제외 — 🔵 out-of-scope)

- `switch (resp.f()) { case "X" }` / `Set.of("A","B").contains(resp.f())` / 정적 `static final Set` contains / 체인 호출 receiver / `equalsIgnoreCase`의 **대소문자 변형 stub 생성(케이스 열거)** → 단계2-A 후속. (equalsIgnoreCase의 리터럴 추출 자체는 REQ-007로 in-scope.)
- concolic 숫자 경계(ASM+Z3) → 단계2-B.
- enum 필드 변형 → 단계2(완료, 본 단계는 회귀 가드만).
- LLM / OpenAPI → 단계3.
- 중첩 객체/배열 응답 DTO 내부 String → 단계1 unsynthesizable-shape loud-fail 유지.
- 병렬 실행 / attach 변형 fuzzing → 단계2 상속(비목표).
