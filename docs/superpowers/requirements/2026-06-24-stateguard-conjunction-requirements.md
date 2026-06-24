# 저장 행 복합 AND 조건 변종 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-stateguard-conjunction-design.md
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) REQ가 모두 ≥1개의 통과 수용 테스트를 가짐.

## 요구사항 목록

### REQ-001 — conjunction 검출 (완전 분류 시 emit)
- 유형: Functional / 우선순위: Must
- 설명: `extractStateGuardConjunctions(srcDir)`가 `CtIf`/`CtConditional`의 top-level `&&` 조건을
  `flattenAnd`로 분해해, **저장행 leaf 2~3개가 모두 분류 성공**할 때 `StateGuardConjunction(classFqn,
  method, line, List<StateGuard> leaves)`를 emit한다.
- 수용기준:
  - Given `if (b.getStatus()==CONFIRMED && b.getTier()==VIP)`, When extract, Then leaves 2개
    (status ENUM, tier ENUM)인 conjunction 1개.
  - Given 저장행 leaf 3개 AND(모두 분류 성공), Then leaves 3개 conjunction 1개.
  - Given 저장행 leaf 4개 AND, Then emit 안 됨(leaf 상한 3 초과 skip).
  - Given leaf가 1개(단일)면, Then conjunction emit 안 됨(기존 단일 가드 경로).
- 검증 레벨: integration (sample-src 픽스처 — `StateGuards.java`에 복합 AND 메서드 추가)

### REQ-002 — leaf 분류 순서·배제
- 유형: Functional / 우선순위: Must
- 설명: leaf 분류는 TEMPORAL→BOOLEAN→NULLITY→ENUM→NUMERIC-상수→NUMERIC-파라미터 순서. TEMPORAL은
  방향(isBefore/isAfter)을 op에 보존. NUMERIC-파라미터·미인식 leaf·OR 노드·부분 분류(leaf 수 ≠
  분류 수)이면 그 조건 통째 skip.
- 수용기준:
  - Given `if (b.getExpiresAt().isBefore(now()) && b.getActive())`, Then expires_at은 TEMPORAL
    (BOOLEAN "before"로 오분류 안 됨), active는 BOOLEAN, op(temporal)="isBefore".
  - Given `if (b.getNights() >= minNights && b.getActive())` (NUMERIC-param 포함), Then skip.
  - Given `if (b.getActive() && (b.getX()||b.getY()))` (OR 포함), Then skip.
- 검증 레벨: integration

### REQ-003 — SeedVariant 모델 확장 + NPE 회피
- 유형: Functional / 우선순위: Must
- 설명: `SeedVariant`에 nullable `StateGuardConjunction conjunction` 필드 추가(2-arg 생성자 후방호환).
  `exploreStateGuardVariants`가 `conjunction!=null`이면 gate 미적용·`discoveredBy="state-guard-conjunction"`,
  **try 본문과 catch 블록 모두** `variant.guard()` 무조건 참조를 null-safe로(catch도 conjunction 식별자).
- 수용기준:
  - Given 기존 `SeedVariant(input, guard)` 호출부·테스트, When 컴파일·실행, Then 불변 green(후방호환).
  - Given conjunction 변종(guard=null)의 시드 INSERT가 예외를 던질 때, When catch 로깅, Then NPE 없이
    conjunction 식별자로 로깅하고 후속 변종 탐색이 abort되지 않음.
- 검증 레벨: integration

### REQ-004 — satisfyingValue 동시 만족 합성
- 유형: Functional / 우선순위: Must
- 설명: conjunction마다 보정된 base 시드 행을 복제해 각 leaf 컬럼을 `satisfyingValue(leaf, col)`로
  **동시 설정**한 변종 1개 생성. 만족값은 kind별(ENUM EQ→positive 첫째, ENUM NE→negated 밖 첫째,
  NUMERIC→`numericParamBaseCol`, BOOLEAN→`Boolean.valueOf(comparand)`, NULLITY→만족쪽, TEMPORAL→
  방향별 날짜). **컬럼 JDBC 타입 변환**(TEMPORAL TIMESTAMP→LocalDateTime/DATE→LocalDate, op=null→
  isBefore 폴백). 변종 PK는 단일 가드 변종 다음 `variantIdx`부터 `offsetPk` 연속.
- 수용기준:
  - Given status·tier ENUM conjunction + base 시드, When synthesizeVariants, Then 변종 시드 1행에
    status=CONFIRMED & tier=VIP 동시 설정, 변종 PK≠base PK(격리).
  - Given TEMPORAL `isBefore` leaf + DATE 컬럼, Then 만족값=과거 `LocalDate(1900,1,1)`(미래 아님);
    TIMESTAMP/DATETIME 컬럼이면 `LocalDateTime`; `op=null`이면 isBefore 폴백. `isAfter`면 미래(2037).
  - Given BOOLEAN leaf, Then 만족값이 Boolean 타입(문자열 "true" 아님).
  - Given NUMERIC-상수 leaf 2개 conjunction, Then 각 만족 경계값(numericParamBaseCol)이 동시 설정.
  - Given 단일 가드 변종 N개 + conjunction 변종, Then conjunction variantIdx가 N부터 연속(전체 PK 중복 0).
- 검증 레벨: integration (ReadInputSynthesizerVariantTest)

### REQ-005 — 같은 컬럼 병합·모순/엣지 skip
- 유형: Functional / 우선순위: Must
- 설명: 한 conjunction에 같은 컬럼 leaf가 여럿이면 제약을 병합해 단일 만족값 산출; 병합 불가(모순)면
  conjunction skip. NOT NULL 컬럼에 null 필요 / 타깃 테이블에 없는 컬럼 / ENUM NE 만족값 없음 → skip.
- 수용기준:
  - Given `status!=PENDING && status!=CANCELLED` (같은 컬럼 NE 2개), Then 만족값이 둘 다 만족하는
    단일 enum 상수(예: CONFIRMED).
  - Given `status==PENDING && status==CONFIRMED` (모순), Then conjunction 변종 미생성(skip).
- 검증 레벨: integration

### REQ-006 — cross-class 귀속
- 유형: Functional / 우선순위: Must
- 설명: `extractStateGuardConjunctions` 결과를 `allStateGuardConjunctions`(입력-필드 `allConjunctions`와
  구분)로 보관, Phase 2 `reachableMethods`/`isReachable`로 엔드포인트에 귀속.
- 수용기준:
  - Given conjunction이 서비스 메서드에 있고 핸들러가 1-hop 호출, When 귀속, Then 그 엔드포인트의
    변종 대상에 포함.
- 검증 레벨: integration

### REQ-007 — early-return·호출 게이트 (conjunction-only)
- 유형: Functional / 우선순위: Must
- 설명: `synthesizeVariants` early-return을 `(guards.isEmpty() && conjunctions.isEmpty()) ||
  base.seeds().isEmpty()`로, `run()` 호출 게이트를 `!stateGuards.isEmpty() || !stateGuardConjunctions.isEmpty()`로
  확장. 단일 가드 없고 conjunction만 있는 엔드포인트도 변종 pass 실행.
- 수용기준:
  - Given conjunction만 있고 단일 가드 없을 때, When `synthesizeVariants`, Then early-return 안 하고
    conjunction 변종 생성(unit).
  - Given conjunction만 있고 `stateGuards` 비어있는 엔드포인트, When `run()`, Then 호출 게이트
    (`!stateGuards.isEmpty() || !stateGuardConjunctions.isEmpty()`)로 `exploreStateGuardVariants` 실행됨.
- 검증 레벨: integration (synthesizeVariants 단위 + runner 게이트)

### REQ-008 — GRB_STATE_GUARDS ablation
- 유형: Functional / 우선순위: Should
- 설명: `GRB_STATE_GUARDS=off`이면 `BuilderCli`가 `extractStateGuardConjunctions` 호출을 동일 env
  게이트로 skip하고 `endpointStateGuardConjunctions`에 빈 리스트 전달 → 변종 no-op(기존 state-guard
  ablation과 동일 배선).
- 수용기준:
  - Given `GRB_STATE_GUARDS=off`, When BuilderCli 인덱싱, Then `allStateGuardConjunctions` 빈 리스트·변종 no-op.
- 검증 레벨: integration

### REQ-009 — 기존 회귀 불변
- 유형: Functional / 우선순위: Must
- 설명: conjunction 추가가 기존 단일 가드(TEMPORAL/ENUM/BOOLEAN/NULLITY/NUMERIC) 검출·합성·gate **동작**을
  바꾸지 않는다. (BuilderIntegrationTest endpoint inventory `containsExactly`는 REQ-010 신규 엔드포인트로
  갱신 — 그 갱신은 REQ-010 범위. 본 REQ는 state-guard path/seed 동작 단언만.)
- 수용기준:
  - Given 기존 `ConstraintExtractorStateGuardTest`/`ReadInputSynthesizerVariantTest`/
    `BuilderCliAttributionTest` 및 `BuilderIntegrationTest` state-guard 동작 단언, When 전체 테스트, Then 불변 green.
  - Given `SeedVariant(input, guard)` 2-arg 호출부(기존 테스트 포함), When 컴파일, Then 오류 없음(2-arg 오버로드 보존).
- 검증 레벨: integration + E2E

### REQ-010 — E2E: order-service 복합 AND 동시 만족 arm
- 유형: Functional / 우선순위: Must
- 설명: **선행조건(구현 task)** — `BookingController`에 `GET /api/bookings/{id}/premium-eligible`
  (endpointId=`get-api-bookings-id-premium-eligible`) 추가: `if (b.getStatus()==CONFIRMED &&
  b.getTier()==VIP) return 200; else throw 404`. + BuilderIntegrationTest endpoint inventory 갱신.
  builder 탐색이 동시 만족 시드 1행으로 200 arm을 연다.
- 수용기준:
  - Given premium-eligible 엔드포인트, When `BuilderIntegrationTest`가 `pathsOf(asset,
    "get-api-bookings-id-premium-eligible")` 조회, Then `discoveredBy="state-guard-conjunction"`,
    expectedStatus=200 path가 존재하고 그 시드 행이 status=CONFIRMED & tier=VIP(동시), requiredSeedIds 비공백.
- 검증 레벨: E2E (BuilderIntegrationTest, order-service)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | conjunction 검출 | ConstraintExtractorConjunctionTest#detect2Leaf, #threeLeafEmit, #fourLeafSkip, #singleNotConjunction | integration | 🔴 planned |
| REQ-002 | 분류 순서·배제 | ConstraintExtractorConjunctionTest#temporalFirst, #numericParamSkip, #orSkip | integration | 🔴 planned |
| REQ-003 | SeedVariant 모델·NPE | ReadInputSynthesizerVariantTest#conjunctionVariant + EndpointExplorationRunnerStateGuardTest#conjunctionCatchNpeAvoided + 2-arg 후방호환 컴파일 | integration | 🔴 planned |
| REQ-004 | satisfyingValue 동시만족 | ReadInputSynthesizerVariantTest#conjunctionSimultaneous, #temporalType, #booleanType, #numericLiteral, #variantIdxContinuous | integration | 🔴 planned |
| REQ-005 | 같은컬럼 병합·skip | ReadInputSynthesizerVariantTest#sameColumnMerge, #contradictionSkip | integration | 🔴 planned |
| REQ-006 | cross-class 귀속 | BuilderCliAttributionTest#conjunctionReachable | integration | 🔴 planned |
| REQ-007 | early-return 게이트 | ReadInputSynthesizerVariantTest#conjunctionOnlyNotSkipped + EndpointExplorationRunnerStateGuardTest#conjunctionOnlyGatePasses | integration | 🔴 planned |
| REQ-008 | GRB ablation | ConstraintExtractorConjunctionTest#ablationOff | integration | 🔴 planned |
| REQ-009 | 기존 회귀 | 기존 StateGuard/Attribution/Variant + BuilderIntegrationTest | integration/E2E | 🔴 planned |
| REQ-010 | E2E 동시만족 arm | BuilderIntegrationTest#premiumEligibleConjunction | E2E | 🔴 planned |

Coverage: 0/10 green (0%) — target 100% (대상: Must 9 + 미연기 Should 1[REQ-008]).
