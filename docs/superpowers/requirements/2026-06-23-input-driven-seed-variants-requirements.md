# 입력-주도 시드 변종 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-23-input-driven-seed-variants-design.md
> 완료 정의(DoD): 커버리지 대상(Must + 미연기 Should) REQ가 모두 ≥1개의 통과 수용
> 테스트를 가짐. petclinic 의존 REQ는 외부 경로라 로컬 스윕으로 검증(아래 명시).
> Phase 태그: 각 REQ에 [P1]/[P2] — PR은 P1 먼저 머지 후 P2(비서 결정).

## 요구사항 목록

> 공통 전제(REQ-001~006 검증용): `sample-src/io/graphrag/sample/guards/StateGuards.java`
> 픽스처와 그 `Booking` 엔티티에 BOOLEAN/NULLITY/NUMERIC 분기 메서드 및 대응 필드
> (boolean `active`, nullable `note`, int `count`/`nights`)를 추가한다(설계 §6 E2E-1a).

### REQ-001 — [P1] BOOLEAN 저장행 가드 검출
- 유형: Functional / 우선순위: Must
- 설명: 저장 행의 boolean getter로 분기하는 if를 StateGuard(kind=BOOLEAN)로 검출한다.
  truthy 단독(`if(b.getActive())`), 부정(`if(!b.getActive())`), `== true/false`를 모두 인식한다.
- 수용기준:
  - Given 픽스처에 `if(b.getActive())`가 있을 때, When `extractStateGuards`, Then
    `kind=BOOLEAN, column="active", op="==", comparandKind=LITERAL, comparand="true"` 가드 1개.
  - Given `if(!b.getActive())`, Then `comparand="false"` (부정 반영).
  - Given `if(b.isActive())` (is-prefix getter), Then `column="active"` (get*/is* 동일 정규화).
  - Given 파라미터/지역변수 boolean 비교(저장행 getter 아님), Then 검출 안 됨.
- 검증 레벨: integration (sample-src 픽스처)

### REQ-002 — [P1] NULLITY 저장행 가드 검출
- 유형: Functional / 우선순위: Must
- 설명: `getter()==null`/`!=null` 분기를 StateGuard(kind=NULLITY)로 검출한다.
- 수용기준:
  - Given `if(b.getNote()==null)`, When extract, Then `kind=NULLITY, column="note", op="=="`.
  - Given `!=null`, Then `op="!="`.
- 검증 레벨: integration

### REQ-003 — [P1] NUMERIC-vs-상수 가드 검출 (음수 포함)
- 유형: Functional / 우선순위: Must
- 설명: `getter() OP 정수리터럴`(OP∈{>,>=,<,<=,==,!=})을 StateGuard(kind=NUMERIC,
  comparandKind=LITERAL)로 검출한다. 음수 리터럴(`CtUnaryOperator` MINUS)도 부호 반영.
  Double/Float 리터럴은 제외(`literalLong` 호환).
- 수용기준:
  - Given `if(b.getCount() > 0)`, Then `kind=NUMERIC, column="count", op=">", comparand="0"`.
  - Given `if(b.getBalance() >= -5)`, Then `comparand="-5"`.
  - Given `if(b.getRate() > 1.5)` (float), Then NUMERIC 가드로 검출 안 됨.
- 검증 레벨: integration

### REQ-004 — [P1] NUMERIC-vs-파라미터 가드 검출
- 유형: Functional / 우선순위: Must
- 설명: `getter() OP paramRef`(paramRef=가드 메서드 파라미터 직접 참조)를
  StateGuard(kind=NUMERIC, comparandKind=PARAM, comparand=파라미터명)로 검출한다.
  중간 계산 경유(`param*2`)는 제외(보수적).
- 수용기준:
  - Given `list(int minNights){ ... if(b.getNights() >= minNights) ...}`, Then
    `kind=NUMERIC, column="nights", op=">=", comparandKind=PARAM, comparand="minNights"`.
  - Given `if(b.getNights() >= minNights*2)`, Then 검출 안 됨(중간 계산).
- 검증 레벨: integration

### REQ-005 — [P1] 종류별 반대-arm 변종 합성 + 격리 PK
- 유형: Functional / 우선순위: Must
- 설명: `flipValues`/`synthesizeVariants`가 BOOLEAN/NULLITY/NUMERIC-상수 가드에 대해
  base + 반대-arm 변종(컬럼당 1개)을 합성하고, 변종마다 `offsetPk`로 격리 PK를 부여한다.
- 수용기준:
  - Given BOOLEAN 가드 + base 시드 active=true, When synthesizeVariants, Then 변종 1개
    (active=false), 변종 PK ≠ base PK.
  - Given NULLITY 가드 + nullable 컬럼 + base non-null, Then 변종 1개(컬럼값=null).
  - Given NULLITY 가드 + nullable 컬럼 + base null, Then 변종 1개(컬럼값=`defaultFor(col)`, 설계 §3.2).
  - Given NULLITY 가드 + NOT NULL 컬럼, Then 변종 없음(skip).
  - Given NUMERIC-상수 `>= 3`, Then 반대 arm 시드값이 OP 불만족값(예: 2).
- 검증 레벨: integration (ReadInputSynthesizerVariantTest)

### REQ-006 — [P1] 입력-시드 공동 합성 (NUMERIC-vs-파라미터)
- 유형: Functional / 우선순위: Must
- 설명: NUMERIC-vs-파라미터 가드에 대해, 입력 파라미터 P=V와 시드 컬럼값을 함께 정해
  양 arm을 연다(op별 결정 규칙: §design 3.3 표). 변종마다 격리 PK.
- 수용기준:
  - Given `getNights() >= minNights` 가드 + base 입력 minNights=V, When synthesizeVariants,
    Then base 시드 nights=V(만족 arm), 변종 시드 nights=V-1(불만족 arm), 각각 vbody의
    minNights=V로 고정(설계 §3.3 op별 표).
  - Given 타깃 테이블 해소 실패(collection 엔드포인트)에 NUMERIC-vs-파라미터 가드와 BOOLEAN
    가드가 함께, Then **NUMERIC-vs-파라미터만 per-guard skip**되고 BOOLEAN 변종은 생성된다
    (현행 whole-method skip을 per-guard skip으로 변경하는 것이 구현 범위 — Sonnet 리뷰).
- 검증 레벨: integration

### REQ-007 — [P1] 종류별 boolean query-param gate
- 유형: Functional / 우선순위: Must
- 설명: 현행 `EndpointExplorationRunner.java:816` gate는 `kind != TEMPORAL` 이분법이라
  NUMERIC/BOOLEAN/NULLITY도 gate=true를 받는다. 이를 3-way로 확장하는 것이 구현 범위 —
  TEMPORAL=false, ENUM=true(기존 유지), BOOLEAN/NULLITY/NUMERIC=미적용(무관 param 미오염).
- 수용기준:
  - Given 3-way gate 구현 후, NUMERIC 가드 변종 + 무관 boolean QUERY param `includeStale`,
    When 변종 실행, Then `includeStale`가 가드로 인해 덮어써지지 않음(§3.3 설정값만 반영).
  - Given 기존 ENUM 가드(delete confirm 케이스), Then gate=true 유지(회귀).
- 검증 레벨: integration (신규 `EndpointExplorationRunnerStateGuardTest#gateByKind*`)

### REQ-008 — [P1] 기존 TEMPORAL/ENUM 회귀 불변
- 유형: Functional / 우선순위: Must
- 설명: 신규 종류 추가가 기존 TEMPORAL/ENUM 검출·합성·gate **동작**을 바꾸지 않는다.
  (BuilderIntegrationTest의 endpoint inventory `containsExactly` 단언은 REQ-009의 신규
  엔드포인트 추가로 인해 갱신되며 — 그 갱신은 REQ-009 범위. 본 REQ는 state-guard 동작 단언만.)
- 수용기준:
  - Given 기존 `ConstraintExtractorStateGuardTest`/`ReadInputSynthesizerVariantTest`
    단언과 `BuilderIntegrationTest`의 state-guard 단언(`get-api-bookings-id` TEMPORAL 404,
    `delete-api-bookings-id` ENUM 409, advance 200/409/410), When 전체 테스트,
    Then 그 단언들이 전부 green(불변).
- 검증 레벨: integration + E2E (BuilderIntegrationTest)

### REQ-009 — [P1] E2E: order-service NUMERIC-vs-파라미터 양 arm
- 유형: Functional / 우선순위: Must
- 설명: `BookingController`에 `GET /api/bookings/{id}/eligibility?minNights={n}`
  (endpointId=`get-api-bookings-id-eligibility`)를 추가한다. 핸들러 본문:
  `if (b.getNights() >= minNights) return 200(eligible); else throw 404("below minNights")`.
  builder 탐색이 그 분기의 양 arm path + 격리 시드를 생성함을 in-repo 검증한다.
  (양 arm을 HTTP status 200/404로 구분해 단언을 명확화 — Cursor I3.)
- 수용기준:
  - Given eligibility 엔드포인트, When `BuilderIntegrationTest#eligibilityNumericTwoArms`가
    `pathsOf(asset, "get-api-bookings-id-eligibility")` 조회, Then nights NUMERIC 가드가
    `op=">=", comparandKind=PARAM, comparand="minNights"`로 검출되고, **expectedStatus=200**
    path(시드 nights=V, 만족 arm)와 **expectedStatus=404** path(시드 nights=V-1, 불만족 arm)가
    각각 생성되며, 두 path의 `requiredSeedIds`가 서로 다른 고유 PK로 격리되고 입력 minNights=V.
- 검증 레벨: E2E (BuilderIntegrationTest, order-service)

### REQ-010 — [P1] StateGuard record 후방호환
- 유형: Non-functional / 우선순위: Should
- 설명: StateGuard 확장(op/comparandKind/comparand nullable)이 기존 7/8-arg 생성자
  호출부·테스트를 깨지 않는다.
- 수용기준:
  - Given 기존 TEMPORAL/ENUM emit 코드와 기존 StateGuard 단위 테스트, When 컴파일·실행,
    Then 변경 없이 green(신규 필드는 null).
- 검증 레벨: integration

### REQ-011 — [P2] 핸들러 1-hop reachable 메서드 추출
- 유형: Functional / 우선순위: Must
- 설명: 핸들러 메서드 본문의 `CtInvocation`에서 직접 호출된 `(declaringTypeFqn, methodName)`
  집합을 추출한다(핸들러 자신 포함). 1-hop만.
- 수용기준:
  - Given 컨트롤러 핸들러가 `service.list(...)`를 호출, When reachableMethods, Then
    집합에 `(ServiceFqn, "list")`와 핸들러 자신이 포함.
- 검증 레벨: integration

### REQ-012 — [P2] cross-class 가드 귀속 (BuilderCli)
- 유형: Functional / 우선순위: Must
- 설명: BuilderCli의 StateGuard/JoinGuard 귀속 필터를 reachable 기반으로 확장한다.
  declaringType이 인터페이스면 `endsWith("." + simpleName)` 폴백. 매칭 실패 시 귀속 0(회귀).
- 수용기준:
  - Given StateGuard가 서비스 메서드에 있고 핸들러가 그 메서드를 1-hop 호출, When 귀속,
    Then 그 가드가 엔드포인트에 귀속되어 변종 실행 대상이 됨.
  - Given 동일 reachable 규칙으로 JoinGuard도 귀속, Then 서비스 계층 JoinGuard도 엔드포인트에
    귀속(StateGuard와 동일 필터 재사용).
  - Given reachable에 없는 가드, Then 귀속 안 됨.
- 검증 레벨: integration (`BuilderCliAttributionTest#reachableIncludesServiceGuard`,
  `#unreachableExcluded`, `#joinGuardReachable`)

### REQ-013 — [P2] E2E: petclinic 계층형 SUT cross-class 변종 개방
- 유형: Functional / 우선순위: Must
- 설명: petclinic에서 cross-class 귀속으로 `ReservationService`의 가드가 컨트롤러
  엔드포인트에 귀속되어 변종 path가 열린다. (a) `list`의 `getNights() >= minNights`(NUMERIC),
  (b) `getById`의 `check_in_date`(TEMPORAL).
- 수용기준:
  - Given Phase 2 구현 전, When `.work/sweep-input-driven.sh before`(petclinic src 경로를
    builder CLI에 전달해 `get-api-reservations`·`get-api-reservations-id` 탐색) 실행, Then
    `ReservationService.list`/`getById` 해당 분기의 `coveredAppBranches`를 `before.json`으로 저장.
  - Given Phase 2 구현 후, When 동일 스윕 `after` 실행, Then `before.json`에 없고 `after.json`에
    있는 분기 라인이 (a) list nights NUMERIC, (b) getById check_in_date TEMPORAL에 해당.
  - (스크립트 경로·CLI 인자·petclinic 경로·지표 산출은 plan task에서 확정.)
- 검증 레벨: local sweep (외부 경로 — CI 밖, 스크립트 before/after 비교)

### REQ-014 — [P2] 파라미터 pass-through 매칭
- 유형: Functional / 우선순위: Should
- 설명: Phase 2 NUMERIC-vs-파라미터에서 핸들러가 서비스를 동명 인자로 pass-through할 때만
  매칭해 입력-시드 공동 합성을 적용하고, 인자명 불일치/계산값 전달이면 skip한다.
- 수용기준:
  - Given 핸들러 `service.list(minNights)` + 서비스 param `minNights` + 엔드포인트 QUERY
    `minNights`, Then 매칭되어 공동 합성.
  - Given 핸들러가 `service.list(req.minStay())`로 다른 이름 전달, Then skip(변종 없음).
- 검증 레벨: integration

## 추적 매트릭스

| REQ-ID | Phase | 요구사항 | 수용 테스트 | Level | Status |
|--------|-------|----------|-------------|-------|--------|
| REQ-001 | P1 | BOOLEAN 검출 | ConstraintExtractorStateGuardTest#booleanGuard* | integration | 🟢 green |
| REQ-002 | P1 | NULLITY 검출 | ConstraintExtractorStateGuardTest#nullityGuard* | integration | 🟢 green |
| REQ-003 | P1 | NUMERIC-상수 검출 | ConstraintExtractorStateGuardTest#numericLiteralGuard* | integration | 🟢 green |
| REQ-004 | P1 | NUMERIC-파라미터 검출 | ConstraintExtractorStateGuardTest#numericParamGuard* | integration | 🟢 green |
| REQ-005 | P1 | 반대-arm 변종+격리PK | ReadInputSynthesizerVariantTest#flip* | integration | 🟢 green |
| REQ-006 | P1 | 입력-시드 공동 합성 | ReadInputSynthesizerVariantTest#inputSeedJoint* | integration | 🟢 green |
| REQ-007 | P1 | kind별 gate | EndpointExplorationRunnerStateGuardTest#gateByKind* | integration | 🟢 green |
| REQ-008 | P1 | 기존 회귀 불변 | 기존 StateGuard 단위 + ReadInputSynthesizerVariantTest + BuilderIntegrationTest(state-guard 단언) | integration/E2E | 🟢 green |
| REQ-009 | P1 | E2E order-service 양arm | BuilderIntegrationTest#eligibilityNumericTwoArms | E2E | 🟢 green |
| REQ-010 | P1 | record 후방호환 | 기존 StateGuard 테스트 컴파일·green | integration | 🟢 green |
| REQ-011 | P2 | reachable 1-hop | ConstraintExtractorReachableTest#oneHopIncludesService, #handlerSelf, #lambdaInvocationIncluded | integration | 🟢 green |
| REQ-012 | P2 | cross-class 귀속 | BuilderCliAttributionTest#reachableIncludesServiceGuard, #unreachableExcluded, #joinGuardReachable, #sameMethodNameDifferentClassExcluded, #handlerSelfIsReachable, #joinGuardUnreachableExcluded, #simpleNameFallbackMatchesWhenFqnEndsWith, #simpleNameFallbackMatchesWhenGuardClassIsSimpleName | integration | 🟢 green |
| REQ-013 | P2 | E2E petclinic 계층형 | local sweep (.work/ 스크립트) | local sweep | 🔴 planned |
| REQ-014 | P2 | pass-through 매칭 | BuilderCliAttributionTest#passThroughSameNameGuardIsReachable, ReadInputSynthesizerVariantTest#inputSeedJoint_geParam(동명 매칭)·#inputSeedJoint_paramNameMismatch_skipsGuard(불일치 skip) | integration | 🟢 green |

Coverage: 13/14 green (P2 진행 중) — Phase 1 대상(REQ-001~010) 10/10 (100% P1 green).
P2 대상(REQ-011~014): REQ-011 🟢, REQ-012 🟢, REQ-013 🔴(petclinic 로컬 스윕 미완), REQ-014 🟢.
비고: REQ-013은 외부 petclinic 의존 — in-repo CI 게이트가 아닌 로컬 스윕으로 green 판정(설계 §6, R4). Task 13 미완료.
비고: BuilderIntegrationTest 두 메서드 동시 실행 시 SUT 포트 충돌 발생(기존 이슈, stash 검증). 단독 실행 green 확인(REQ-009).
