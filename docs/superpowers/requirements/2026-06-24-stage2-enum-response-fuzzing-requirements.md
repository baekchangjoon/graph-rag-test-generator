# 단계2 enum 상수 조합 응답 변형 fuzzing 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-24-stage2-enum-response-fuzzing-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green) + 단계1 회귀 green

## 요구사항 목록

### REQ-001 — enum 변형으로 모든 arm 도달
- 유형: Functional / 우선순위: Must
- 설명: 응답 DTO의 enum 필드 각 상수를 변형 stub으로 등록·재invoke해, 그 enum으로 갈리는 SUT 분기의 모든 arm을 도달한다.
- 수용기준:
  - Given order-service를 단계2 빌더로 `--external-stubs` 없이 탐색(`InventoryResponse.mode: FulfillmentMode{STANDARD,EXPRESS_ONLY,BACKORDER}`), When 3상수를 변형 stub으로 등록·재invoke, Then POST `/api/orders` 커버리지에 switch 3 arm이 모두 covered(단계1 첫 상수만 대비 arm 증가)이다.
- 검증 레벨: E2E black-box

### REQ-002 — 결정성
- 유형: Non-functional / 우선순위: Must
- 설명: 변형 생성·측정 순서가 결정적이라 동일 commit 2회 실행이 동일 결과.
- 수용기준:
  - Given 동일 commit 2회 빌드, When 변형 집합(label)·커버리지 비교, Then 동일하다.
- 검증 레벨: E2E black-box

### REQ-003 — budget 절단 loud (silent cap 금지)
- 유형: Functional / 우선순위: Must
- 설명: 변형 수가 budget을 초과하면 잘라내되 절단량을 리포트에 기록한다.
- 수용기준:
  - Given budget=2 + 3개 이상 변형, When generate, Then `response-variant-budget-truncated ... kept=2 dropped=M`가 기록되고 kept=2다.
- 검증 레벨: integration (unit)

### REQ-004 — 변형 provenance 태깅
- 유형: Functional / 우선순위: Must
- 설명: 변형 stub 경유 캡처도 `responseProvenance=SYNTHESIZED`로 판정된다(전역 미등록이어도).
- 수용기준:
  - Given 변형 stub(헤더 매칭, 전역 Set 미등록)으로 통과한 외부 호출, When 캡처, Then `responseProvenance==SYNTHESIZED`.
- 검증 레벨: integration

> **범위 각주(2026-07-28):** 대상은 stub 경유 관측 캡처다 — 이후 REQ-F012-006/007이 도입한
> egress-assertion 산출물(`pathId` 접미 `-egressassert`)은 계약 기반 산출물이라
> `provenance=CONTRACT`이며 이 요구의 대상이 아니다. 실측 근거와 경위:
> `docs/superpowers/followup/2026-07-28-stage2-provenance-assertion-drift.md`.

### REQ-005 — 단계1 회귀 없음
- 유형: Non-functional / 우선순위: Must
- 설명: SUT fixture 확장(mode 추가) 후에도 단계1 E2E가 green을 유지한다.
- 수용기준:
  - Given mode stub 갱신된 fixture, When `Stage1ExternalStubSynthesisE2E` 실행, Then green.
- 검증 레벨: E2E black-box

### REQ-006 — EnumResponseVariantGenerator
- 유형: Functional / 우선순위: Must
- 설명: 응답 BodyShape + enumConstants → 변형 목록. 단일 필드 변형 우선(각 상수 최소 1회), 2-way 조합 후순, budget 절단, 결정적 label.
- 수용기준:
  - Given 단일 enum 3상수, When generate(budget≥3), Then 3변형(첫 상수 baseline 제외 시 2) label 결정적.
  - Given enum 2필드, When generate, Then 단일 필드 변형이 2-way 조합보다 먼저 채워진다.
- 검증 레벨: integration (unit)

### REQ-007 — trace-id 격리 매칭 조건 (TraceKey.matchFor)
- 유형: Functional / 우선순위: Must
- 설명: `TraceKey.matchFor(traceId)`가 모드별 WireMock 매처를 준다 — otel `containing(traceId)`(traceparent 전체 값 substring), sleuth `equalTo(traceId)`, none `null`.
- 수용기준:
  - Given otel trace-id, When matchFor, Then `containing(traceId)`이고 `00-<tid>-<sid>-01` 전체 값에 매칭한다.
  - Given none, When matchFor, Then null.
- 검증 레벨: integration (unit)

### REQ-008 — 변형 stub 등록/제거 (registerVariant/removeVariant)
- 유형: Functional / 우선순위: Must
- 설명: 변형 stub을 `(method,path,withHeader)` + 전역보다 높은 priority로 등록(StubId 반환), `removeStub(StubId)`로 제거. 단계1 전역 `registered` Set과 분리된 별도 추적 Map.
- 수용기준:
  - Given 같은 (method,path)에 변형 2개, When registerVariant 2회, Then 둘 다 등록(멱등 차단 안 됨), 각 trace-id 요청에 해당 변형 응답.
  - Given 등록된 변형, When removeVariant(id), Then 그 stub 제거되고 전역 stub으로 복원.
- 검증 레벨: integration

### REQ-009 — 변형 탐색 루프 (커버리지 유도·수렴)
- 유형: Functional / 우선순위: Must
- 설명: B2 수렴 후 enum 응답 path에 변형 루프 — 각 변형 invoke·delta 측정, 새 arm 연 변형 보존, `cumulativeCoverage` OR-병합, budget 수렴.
- 수용기준:
  - Given enum 응답 path, When 변형 루프, Then 새 arm 연 변형이 보존되고 cumulativeCoverage가 변형 간 누적(앞 변형 arm이 missedBranches에서 빠짐).
  - Given budget 소진, When 루프, Then 종료.
- 검증 레벨: integration

### REQ-010 — none 모드 순차 교체 + 전역 보존
- 유형: Non-functional / 우선순위: Should
- 설명: `--trace-mode none`에서 trace-id 없이 변형마다 순차 stub 교체(전역 stub 보존, 변형만 등록·제거)로 동작한다.
- 수용기준:
  - Given none 모드, When 변형 루프, Then 전역 stub 삭제 없이 변형 순차 교체로 각 arm 도달.
- 검증 레벨: integration

### REQ-011 — SUT fixture 확장 + 기존 테스트 갱신
- 유형: Functional / 우선순위: Must
- 설명: order-service `InventoryResponse`에 `mode` enum 추가 + `OrderController` switch 분기(available 분기 STANDARD arm 보존). 기존 inventory stub을 주는 테스트의 응답을 `{available,mode}`로 갱신.
- 수용기준:
  - Given mode 추가, When order-service 빌드·기존 테스트(`OrderExpressApiTest` 등) 실행, Then 역직렬화 NPE 없이 green.
- 검증 레벨: integration

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | enum 변형 모든 arm 도달 | `Stage2EnumResponseFuzzingE2E#enumVariantsReachAllArms` | E2E | 🟢 |
| REQ-002 | 결정성 | `Stage2EnumResponseFuzzingE2E#deterministicAcrossRuns` | E2E | 🟢 |
| REQ-003 | budget 절단 loud | `EnumResponseVariantGeneratorTest#budgetTruncationLoud` | unit | 🟢 |
| REQ-004 | 변형 provenance (범위 각주 참조) | `Stage2EnumResponseFuzzingE2E#variantStubCapturesAreSynthesized` | E2E | 🟢 (2026-07-28 재확인 — 그 전까지 main에서 상시 실패였다: `docs/superpowers/followup/2026-07-28-stage2-provenance-assertion-drift.md`) |
| REQ-005 | 단계1 회귀 | `Stage1ExternalStubSynthesisE2E`(mode stub 갱신 후, 4 tests green) | E2E | 🟢 |
| REQ-006 | VariantGenerator | `EnumResponseVariantGeneratorTest` | unit | 🟢 |
| REQ-007 | trace-id 매칭 조건 | `TraceKeyMatchForTest` | unit | 🟢 |
| REQ-008 | registerVariant/removeVariant | `ExternalStubVariantTest` | integration | 🟢 |
| REQ-009 | 변형 탐색 루프 | `EnumVariantReExploreTest` | integration | 🟢 |
| REQ-010 | none 모드 순차 교체 | `EnumVariantNoneModeTest` | integration | 🟢 |
| REQ-011 | SUT fixture + 기존 테스트 갱신 | `OrderExpressApiTest`(갱신) + order-service 빌드 | integration | 🟢 |

Coverage: 11/11 green (100%) — target 100% (대상: Must 9 + Should 2, 모두 미연기). Won't/deferred 없음.

## 단계 경계 (이 명세에서 제외 — 🔵 out-of-scope)

- status 자유 String 리터럴 변형 / concolic 숫자 경계 → 단계2 후속.
- LLM / OpenAPI → 단계3.
- 중첩 객체 응답 DTO → 단계1 unsynthesizable-shape loud-fail 유지.
- 3-way+ enum 카르테시안 / 병렬 실행 / attach 변형 fuzzing → 비목표.
