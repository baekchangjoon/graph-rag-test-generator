# 상태머신 다중 전이 arm (작업 #5)

작성: 2026-06-16 · 브랜치: worktree-feat-statemachine-transitions · 기반: #40 머지 후

## 1. 문제 / 동기
Stage 4의 `extractStateGuards`/`synthesizeVariants`(상태-의존 가드 양-arm 시드)는 두 한계가 있다:
1. **ENUM NE 가드만**(`getter() != A && != B`). `== 가드`(`getter() == CONFIRMED`, 긍정 전이 분기)는
   "반대-arm 의미가 달라 v1 제외"(ConstraintExtractor L354). → 상태 전이 핸들러의 `if (status == X)` arm을
   못 연다.
2. **가드당 변종 1개**(잔여 첫 상수). 한 enum 컬럼이 **여러 상태값**으로 갈리는 다중 전이
   (`if (status==PENDING) confirm(); else if (status==CONFIRMED) complete();`)에서 각 상태 arm을 다 못 연다.

즉 상태머신의 **다중 전이**(상태값마다 다른 분기)를 단일 변종으로는 커버하지 못한다.

실측 현황(정직): order-service `BookingController` DELETE는 NE 가드(`!= PENDING && != CANCELLED`)로 이미
잔여 상수(CONFIRMED) 1개라 단일 변종으로 충분. MSA 6종은 enum status 비교 가드(`==`/`!=`/switch)가 희소
(community/diary grep 0). 따라서 **주 실증은 in-repo 벤치마크**이고, 실효는 향후 상태머신-heavy SUT + `==`
가드 지원이라는 기능 갭 해소다(R3에 명시).

## 2. 목표 / 비목표
- **목표**:
  - **ENUM_EQ(`==`) 가드 추출**: `getter() == CONST` → 그 CONST로 시드해 해당 전이 arm을 연다.
  - **다중 상태 변종**: 한 (class,method,column)에 여러 상태값 분기(여러 `==` 상수, 또는 NE 가드의 여러
    잔여 상수)가 있으면 **각 상태값마다 변종**을 만들어 모든 전이 arm을 연다.
- **비목표**:
  - `switch`/`tableswitch`·`String.equals` enum 매핑(바이트코드 패턴 별개, 드묾) — 후속.
  - 전이표/가드 조합(전이 A→B 허용 여부 등 복합 로직) — 단일 컬럼 상태값 기준만.
  - TEMPORAL 가드 — 무변경(기존 동작 유지).
  - happy 경로가 이미 통과하는 상태값(중복 변종) 회피.

## 3. 접근
1. **`ConstraintExtractor.extractStateGuards`**: 기존 NE 누적에 더해 **`== 가드` 추출**. `getter() == CONST`
   (`BinaryOperator` `==`, 한 쪽이 enum 상수 접근)을 (class,method,column)별로 모아 `positiveConstants` 집합
   구성. `StateGuard`에 필드 추가(또는 `GuardKind.ENUM_EQ` 분리) — NE는 `negatedConstants`,
   EQ는 `positiveConstants`. 한 컬럼에 NE와 EQ가 공존하면 둘 다 보존.
2. **`ReadInputSynthesizer.synthesizeVariants`**: 가드별 변종 생성을 **상태값 집합**으로 일반화.
   - NE 가드: 기존(잔여 상수)에서 **잔여 상수 전체**(또는 상한 N개)로 확장 → 각 변종.
   - EQ 가드: `positiveConstants`의 **각 상수**로 변종(그 상수로 시드 → `== CONST` arm 진입).
   - 변종 PK는 기존처럼 offset으로 충돌 회피. happy가 이미 그 상태면 skip(중복 회피).
3. **`flipValue`**(현 ENUM=잔여 첫 상수): EQ는 입력 상수 그대로, NE는 잔여 상수들. 변종이 여러 개이므로
   `flipValue` → `flipValues`(리스트) 반환으로 일반화.

## 4. 설계 결정
- **D1 EQ/NE 통합 vs 분리**: `StateGuard`에 `positiveConstants`(EQ) + `negatedConstants`(NE) 둘 다 두고
  `GuardKind.ENUM` 하나로 통합(TEMPORAL은 그대로). 변종 합성이 두 집합을 각각 순회. 분리(ENUM_EQ kind)보다
  한 컬럼 혼합 가드를 자연 처리.
- **D2 변종 수 상한**: enum 상수가 많으면(상태 10+) 변종 폭발 → 상한(예: 컬럼당 최대 4 변종, 결정적 정렬 순).
  초과 시 `log`로 드롭 명시(silent truncation 금지).
- **D3 중복 회피**: happy 시드의 상태값과 같은 변종은 생성 안 함(happy가 이미 그 arm). 결정성 위해 상수 정렬.
- **D4 무회귀**: TEMPORAL·기존 NE-단일-잔여 동작은 보존. order-service DELETE(잔여 CONFIRMED 1개)는 동일.

## 5. E2E / 수용 테스트 (정의된 done)
**outer-loop(먼저 RED)**: order-service `BookingController`에 **다중 상태 == 전이 핸들러** 추가(또는 신규
경량 핸들러). 예: `@PostMapping("/{id}/advance")` —
```java
Booking b = bookings.findById(id).orElseThrow(404);
if (b.getStatus() == BookingStatus.PENDING)   return 200 "confirmed";   // PENDING arm
if (b.getStatus() == BookingStatus.CONFIRMED) throw 409 "already advanced"; // CONFIRMED arm
// CANCELLED → 410 gone (fallthrough)
throw new ResponseStatusException(GONE, "cancelled");
```
세 상태(PENDING/CONFIRMED/CANCELLED)가 각각 다른 arm(200/409/410). 빌더가 각 상태 변종 시드로 세 arm 모두 캡처.

`BuilderE2eTest` 수용 단언:
- `post-api-bookings-id-advance`(또는 id)에 expectedStatus가 **200·409·410을 모두 포함**(다중 전이 arm).
- 각 arm의 시드 행 status 값이 PENDING/CONFIRMED/CANCELLED로 구별(변종 시드 증거).
- **회귀 가드**: 다중 변종 미적용으로 되돌리면 한 상태(happy)만 → 1 arm → 단언 FAIL.

**inner-loop(단위 TDD)**: (i) `extractStateGuards`가 `== CONST` 가드를 `positiveConstants`로 추출,
(ii) 한 컬럼 다중 `==` → 다중 positive, (iii) `synthesizeVariants`가 EQ 각 상수로 변종(N개), (iv) NE 가드
잔여 상수 다중 변종, (v) happy 상태 중복 변종 skip, (vi) 변종 상한 초과 시 드롭+log, (vii) TEMPORAL 무변(회귀).

**done(A — in-repo, CI)**: 위 E2E + 단위 GREEN, order-service e2e 전체 GREEN(기존 DELETE NE 가드 단언 유지).
**done(B — 외부 스윕)**: petclinic/대표 MSA builder — 기존 상태 가드 무변, 크래시 0.

## 6. 회귀 (regression-on-sut-expansion)
- order-service: e2e 전체 + `BuilderE2eTest`(기존 DELETE NE `CONFIRMED` 변종 단언 + TEMPORAL stale 단언 유지).
- petclinic/MSA: builder 전 사이클 — enum 가드 없는 SUT 무변, 크래시 0.

## 7. 위험
- **R1 변종 폭발**: 다중 상태 × 다중 컬럼 → 시드/탐색 비용. 완화: D2 상한 + 결정적 정렬.
- **R2 EQ 가드 false-positive**: `==` 가 enum이 아닌 비교(객체 동일성 등)일 때 오추출. 완화: 한 쪽이 enum 상수
  접근(`enumTypeAccess`)일 때만 — 기존 NE 추출과 동일 가드.
- **R3 실측 실효 제한**: 실측 SUT에 enum 전이 가드 희소 → order-service 벤치마크가 주 실증. `==` 가드 지원은
  기능 갭 해소(범용)이고 코어 리스크 없음(추출기/시드 확장). 정직하게 명시.

## 8. 3-모델 리뷰 triage (Sonnet / Gemini 3.5 Flash / GPT-5.2)
세 모델 모두 needs_revision — 방향(== 지원·다중 변종)은 sound하나 변종 규칙·스키마·dedup·벤치마크 정밀화 필요.
코어 리스크는 없음(추출기/시드 확장). 전부 반영.

**반영(critical):**
- **EQ 변종 규칙 명확화**(GPT I1, Gemini I3): EQ 가드의 변종 = `positiveConstants` 각 상수(happy 제외) **+
  잔여 상수(positive에도 negated에도 없는 enum 상수) 1개**(else/false-arm). 후자가 없으면 생략. 벤치마크의
  fallthrough/else arm(예 CANCELLED)을 열려면 이 잔여 변종이 필수. happy가 어떤 positive와 같으면 그 arm은
  main happy가 커버(중복 변종 skip).
- **StateGuard 스키마 + 후방호환**(Sonnet I1, Gemini I1): record에 `List<String> positiveConstants` 추가
  (8-arg). 기존 7-arg 호출부 보존을 위해 **7-arg 보조 생성자**(positiveConstants=List.of()) 추가 →
  `ReadInputSynthesizerVariantTest`·기존 emit 무변. `EnumGuardAcc`에 positive 집합 누적 추가.
- **happy 상태 dedup 결정적**(Sonnet I3, GPT I3): `base` targetRow에서 `guard.column()` 값을
  case-insensitive로 찾아 happy 상태값으로 본다. 없으면 "unknown"(skip 안 함). String이면 enum 상수명으로
  정규화 후 비교. EQ/NE 동일 루틴.
- **EQ-only 폭발 방지**(Gemini I2): NE 잔여 변종은 `negatedConstants`가 **비어있지 않을 때만** 생성(EQ-only
  컬럼에서 전 enum 폭발 방지).
- **전역 variantIdx·PK 충돌 회피**(Sonnet I2): `flipValue`→`flipValues(guard, base상태)` 리스트 반환,
  synthesizeVariants는 (guard 외부 루프 × 상태값 내부 루프)에서 **전역 단일 variantIdx**를 변종마다 증가 →
  EQ/NE가 같은 상태명을 내도 offset PK가 전역 고유.

**반영(important):**
- **벤치마크 containsExactly 갱신**(Sonnet I4, GPT I2): 신규 `post-api-bookings-id-advance`를
  `BuilderE2eTest` L51 `containsExactly` 정렬 위치에 삽입(기존 12→13개).
- **변종 선택/상한 결정적**(Sonnet I6, GPT I4): 변종 후보 = `[positive 정렬] + [잔여 1개]`(base 제외),
  앞에서 컬럼당 최대 K=4. 초과 시 드롭 상수 `log`(silent truncation 금지).
- **POST advance arm 메커니즘**(Sonnet I5): happy `status`=enumColumns 첫(PENDING)→200(main orchestrator),
  CONFIRMED 변종→409, CANCELLED 잔여 변종→410. POST엔 boolean QUERY gate 없음(gate no-op, 정상).
- **변종 행 cleanup**(Sonnet I7): 변종 invoke 후 그 offset-PK 행 best-effort DELETE(happy resetSeeds 대칭) —
  다중 변종 누적 방지. happy 행은 보존.
- **NE 다중 잔여 무조건**(Sonnet I8): NE 잔여가 여럿이면 전부(상한 내) 변종 — 행동 변경. order-service
  DELETE(잔여 CONFIRMED 1개)는 동일.
- **예산**(GPT I5): 추가 HTTP 호출 ≤ Σ_endpoints min(K, candidateStates). enum 가드 endpoint만(희소) → CI 영향 미미.

**반영(recommended):** 벤치마크 유효 Spring MVC 코드(Gemini I4, ResponseStatusException/ResponseEntity).
switch는 흔하나 이번 단계 비목표 명시(Gemini I5).

**단위 (viii) 추가**(Sonnet I9): NE+EQ 혼합 컬럼(`!= A` && `== B`) → StateGuard 하나에 negated=[A]·positive=[B].

## 9. 구현 노트 (코드 동기)
- **변종 행 cleanup(§8 important 조정)**: 실측 E2E GREEN으로 변종 시드 행 누적이 무해함을 확인 — 변종 PK는
  엔드포인트별 probeId 기반 offset으로 전역 고유하고 분석 DB는 탐색 후 폐기(Testcontainers)된다. 명시적
  best-effort DELETE는 코드 추가 리스크 대비 이득이 적어 **보류**(후속). 누적은 같은 엔드포인트의 변종 N개뿐.
- **happy arm**: order-service `status` enumColumns 첫 상수가 happy 상태 → advance의 그 arm은 main
  orchestrator가, 나머지 두 상태는 변종 시드가 캡처(200/409/410 모두 관측 — E2E 확인).
- **회귀 결과**: order-service e2e 53/53 GREEN + `BuilderE2eTest`(advance 200/409/410 + 기존 DELETE NE
  CONFIRMED·TEMPORAL stale 단언 유지) GREEN. petclinic 145/253·24 endpoints·크래시 0(무변). MSA는 enum
  `==`/`!=` 상태 가드 부재로 정적 무변(EQ 추출 미발동·NE 동작 동일).
