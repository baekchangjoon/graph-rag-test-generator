# 단계2-A status-style String 리터럴 응답 변형 fuzzing 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-stage2-string-literal-response-fuzzing-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green) + 단계1·단계2 회귀 green (enum 변형 label byte-동일)

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
  - Given 동일 commit 2회 빌드, When 변형 집합(label)·커버리지 비교, Then byte-동일하다.
- 검증 레벨: E2E black-box

### REQ-003 — 비동치/미해석 비교 loud skip (silent drop 금지)
- 유형: Functional / 우선순위: Must
- 설명: 동치(equals-family) 외 비교(`startsWith`/`contains` 부분문자열/변수 비교)와 미해석 상수(외부/no-classpath `CtFieldRead`), 접근자 모호(동명 String 필드 2+ DTO)는 후보로 추출하지 않고 각각 loud 로그를 남긴다.
- 수용기준:
  - Given `startsWith`/변수 비교 분기, When 추출, Then 후보 미생성 + `string-literal-nonequality-skipped` 로그.
  - Given 외부/미해석 `static final String` 참조 비교, When 추출, Then 후보 미생성 + `string-literal-const-unresolvable` 로그.
  - Given 동명 String 필드를 가진 DTO 2개 이상, When 추출, Then 그 필드 skip + `string-literal-accessor-ambiguous` 로그.
- 검증 레벨: integration (unit)

### REQ-004 — 변형 provenance 태깅
- 유형: Functional / 우선순위: Must
- 설명: String 변형 stub 경유 캡처도 `responseProvenance=SYNTHESIZED`로 판정된다(전역 미등록이어도). 단계2 메커니즘 재사용.
- 수용기준:
  - Given String 변형 stub(헤더 매칭, 전역 Set 미등록)으로 통과한 외부 호출, When 캡처, Then `responseProvenance==SYNTHESIZED`.
- 검증 레벨: E2E black-box

### REQ-005 — 단계1·단계2 회귀 없음 (enum byte-동일)
- 유형: Non-functional / 우선순위: Must
- 설명: SUT fixture 확장(region 추가)·생성기 통합(`ResponseFieldVariantGenerator`) 후에도 단계1·단계2 E2E가 green을 유지하고, enum-only 변형 label·산출물이 단계2와 byte-동일하다.
- 수용기준:
  - Given region stub 갱신·생성기 통합, When `Stage1ExternalStubSynthesisE2E`·`Stage2EnumResponseFuzzingE2E` 실행, Then 전부 green.
  - Given 동일 enum 입력, When `ResponseFieldVariantGenerator.generate`(enum 경로), Then 출력 label·순서가 단계2 `EnumResponseVariantGenerator`와 동일하다.
- 검증 레벨: E2E black-box + integration (unit)

### REQ-006 — budget 절단 loud (silent cap 금지)
- 유형: Functional / 우선순위: Must
- 설명: enum+String 합친 변형 수가 budget을 초과하면 잘라내되 절단량을 loud 로그로 기록한다. 단일 필드 변형은 카르테시안보다 먼저 채워 절단에서 보호.
- 수용기준:
  - Given budget=2 + 3개 이상 변형, When generate, Then `response-variant-budget-truncated ... kept=2 dropped=M`가 기록되고 kept=2다.
  - Given enum 1필드 + String 1필드, When generate, Then 두 단일 필드 변형이 2-way 조합보다 먼저 채워진다.
- 검증 레벨: integration (unit)

### REQ-007 — ResponseStringLiteralExtractor (equals-family 추출)
- 유형: Functional / 우선순위: Must
- 설명: SUT `CtModel`(no-classpath)에서 응답 DTO String 필드 접근자(simple-name 키)에 대한 equals-family 동치 비교 리터럴을 결정적으로 수집한다. `ConstraintExtractor`의 `fieldRef`/`stringLiteral` 헬퍼 재사용. 출력 `Map<dtoFqn, Map<field, List<literal>>>` 정렬·중복제거.
- 수용기준:
  - Given `resp.f().equals("X")` / `"X".equals(resp.f())`, When extract, Then field f → ["X"].
  - Given `resp.f().equalsIgnoreCase("X")`, When extract, Then field f → ["X"](리터럴 원문).
  - Given `Objects.equals(resp.f(), "X")`, When extract, Then field f → ["X"].
  - Given `String r = resp.f(); ... "X".equals(r)`(단순 로컬 바인딩), When extract, Then field f → ["X"].
  - Given 동일 소스트리 `static final String C = "X"; ... C.equals(resp.f())`, When extract, Then field f → ["X"].
- 검증 레벨: integration (unit)

### REQ-008 — 변형 plan 생성기 통합 (ResponseFieldVariantGenerator)
- 유형: Functional / 우선순위: Must
- 설명: `EnumResponseVariantGenerator` → `ResponseFieldVariantGenerator` rename. `generate(Map<field,List<candidate>> nonBaselineCandidates, budget)`로 일반화 — baseline 제외는 호출자 책임(enum=선언순 첫 상수, String=`scalarValue(field)`). 카르테시안·budget·결정적 label 코어 1곳.
- 수용기준:
  - Given non-baseline 후보 맵(단일 필드 2값), When generate(budget≥2), Then 2변형 label 결정적.
  - Given enum 1필드 + String 1필드 후보, When generate, Then 단일 필드 변형이 먼저, enum×String 2-way가 후순.
- 검증 레벨: integration (unit)

### REQ-009 — 변형 루프 통합 + 후보맵 조립 (runResponseVariantLoops)
- 유형: Functional / 우선순위: Must
- 설명: `runEnumResponseVariantLoops` → `runResponseVariantLoops`. 각 site에서 enum 후보(상수−first)와 String 후보(`stringLiteralsByDto[shape.javaType()][field]`−`scalarValue(field)`)를 필드별로 합쳐 plan 생성. 리터럴 0건 String 필드는 후보 없음(변형 0). 변형 path/http id 접두사는 중립 `responsevar`.
- 수용기준:
  - Given enum+String 필드를 가진 응답 path, When 루프, Then enum·String 변형이 같은 cumulativeCoverage에 OR-병합되고 각 새 arm 보존.
  - Given 리터럴 0건 String 필드, When 후보맵 조립, Then 그 필드 변형 0(정상).
- 검증 레벨: integration

### REQ-010 — 인덱싱 배선 (StaticIndex 경유 전달)
- 유형: Functional / 우선순위: Must
- 설명: `ResponseStringLiteralExtractor` 산출 `stringLiteralsByDto`를 enumConstants와 동일 패턴으로 `StaticIndexBundle`/`StaticIndex`에 싣고 `IndexCache` 직렬화 + `BuilderCli`→explore 배선 + `EndpointExplorationRunner`로 전달한다.
- 수용기준:
  - Given 빌드, When indexStatically→explore, Then `EndpointExplorationRunner`가 `stringLiteralsByDto`를 수신한다.
  - Given 직렬화 round-trip, When `StaticIndexSerde` 저장·로드, Then `stringLiteralsByDto` 보존(byte-동일).
- 검증 레벨: integration

### REQ-011 — SUT fixture 확장 + 기존 테스트 갱신 (SWITCH_LINE 재측정)
- 유형: Functional / 우선순위: Must
- 설명: order-service `InventoryResponse`에 `String region` 추가 + `OrderController`의 EXPRESS 블록 switch **앞**에 `if ("EMBARGOED".equals(stock.region()))` 422 분기. 기존 inventory stub을 `{available,mode,region}`로 갱신. region 분기 삽입으로 라인이 밀리므로 `Stage2EnumResponseFuzzingE2E`의 `SWITCH_LINE` 상수 재측정. graph-rag-builder 단위 테스트 `sample-src` 픽스처에도 region/equals 분기 추가.
- 수용기준:
  - Given region 추가·stub 갱신, When order-service 빌드·기존 테스트(`OrderExpressApiTest` 등) 실행, Then 역직렬화 NPE 없이 green.
  - Given region 분기 삽입으로 라인 시프트, When `Stage2EnumResponseFuzzingE2E` 실행, Then `SWITCH_LINE` 갱신 후 enum 3-arm 단언 green.
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
| REQ-009 | 변형 루프 통합·후보맵 | `StringLiteralVariantReExploreTest` | integration | 🔴 planned |
| REQ-010 | 인덱싱 배선 | `StaticIndexStringLiteralSerdeTest` + `BuilderCli` 배선 | integration | 🔴 planned |
| REQ-011 | SUT fixture + SWITCH_LINE 재측정 | `OrderExpressApiTest`(갱신) + `Stage2EnumResponseFuzzingE2E`(갱신) | integration | 🔴 planned |

Coverage: 0/11 green (0%) — target 100% (대상: Must 11, 미연기 Should 0). Won't/deferred 없음.

## 단계 경계 (이 명세에서 제외 — 🔵 out-of-scope)

- `switch (resp.f()) { case "X" }` / `Set.of("A","B").contains(resp.f())` / 정적 `static final Set` contains / 체인 호출 receiver / `equalsIgnoreCase`의 케이스 열거 → 단계2-A 후속.
- concolic 숫자 경계(ASM+Z3) → 단계2-B.
- enum 필드 변형 → 단계2(완료, 본 단계는 회귀 가드만).
- LLM / OpenAPI → 단계3.
- 중첩 객체/배열 응답 DTO 내부 String → 단계1 unsynthesizable-shape loud-fail 유지.
- 병렬 실행 / attach 변형 fuzzing → 단계2 상속(비목표).
