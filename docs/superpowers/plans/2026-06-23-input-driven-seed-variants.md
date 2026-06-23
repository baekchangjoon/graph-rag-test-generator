# 입력-주도 시드 변종 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development 또는
> executing-plans로 task별 구현. 스텝은 체크박스(`- [ ]`)로 추적.
> 출처: design `docs/superpowers/specs/2026-06-23-input-driven-seed-variants-design.md`,
> requirements `docs/superpowers/requirements/2026-06-23-input-driven-seed-variants-requirements.md`

**Goal:** GET 탐색에서 BOOLEAN/NULLITY/NUMERIC 데이터-상태 분기를 검출·변종 합성(입력-시드
공동)하고, cross-class 귀속으로 계층형 SUT(petclinic)에서도 변종이 열리게 한다.

**Architecture:** ConstraintExtractor에 GuardKind 3종 + ComparandKind 추가(검출),
ReadInputSynthesizer.flipValues/synthesizeVariants 확장(합성·입력-시드 공동),
EndpointExplorationRunner gate 3-way, BuilderCli reachable 1-hop 귀속(Phase 2).

**Tech Stack:** Java 17, Spoon(AST), JUnit5, AssertJ, Gradle.

## Global Constraints

- 정수 리터럴만(`literalLong` 호환, Double/Float 제외). 음수는 `CtUnaryOperator`(MINUS) 언랩.
- 변종 격리: `offsetPk`로 고유 PK(오버플로 시 `max-(CAP-idx)` clamp).
- 기존 TEMPORAL/ENUM 검출·합성·gate·테스트 불변(회귀 0).
- 모든 가드 검출은 저장행 getter(`getterRef`)만 — pure-input 비교 제외.
- 커밋 author: `baekchangjoon <changjoon.baek@icloud.com>`.
- 변종은 best-effort(실패=회귀 아님).

---

## Phase 1 (P1) — 가드종류확장 + 입력-시드 공동 (먼저 머지)

### Task 1: E2E 골격 — order-service eligibility 엔드포인트 + 실패 E2E

**REQ-IDs:** REQ-009 (외부 루프 red)

**Files:**
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/BookingController.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderIntegrationTest.java`

**Interfaces:**
- Produces: 엔드포인트 `GET /api/bookings/{id}/eligibility?minNights={n}`
  (endpointId=`get-api-bookings-id-eligibility`), 핸들러 분기
  `if (b.getNights() >= minNights) return 200; else throw 404("below minNights")`.

- [ ] **Step 1:** BookingController에 eligibility 핸들러 추가:
```java
@GetMapping("/{id}/eligibility")
public BookingResponse eligibility(@PathVariable Long id, @RequestParam int minNights) {
    if (id == null || id <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id must be a positive integer");
    }
    Booking b = bookings.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
    if (b.getNights() >= minNights) {
        return toResponse(b);
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " below minNights " + minNights);
}
```
- [ ] **Step 2:** `BuilderIntegrationTest#eligibilityNumericTwoArms` 작성(red): asset에서
  `pathsOf(asset,"get-api-bookings-id-eligibility")`로 path 조회 →
  expectedStatus 200(시드 nights=V) path와 404(nights=V-1) path 각 1개, requiredSeedIds 격리 단언.
  기존 endpoint inventory `containsExactly`에 새 endpointId 추가(REQ-008 범위 분리 반영).
- [ ] **Step 3:** Run `./gradlew :graph-rag-builder:test --tests "*BuilderIntegrationTest.eligibilityNumericTwoArms"`.
  Expected: FAIL (NUMERIC 가드 미검출 → 변종 0, 404 arm 없음).
- [ ] **Step 4:** Commit `test(e2e): order-service eligibility NUMERIC-param 양arm E2E (red) [REQ-009]`.

### Task 2: ComparandKind + StateGuard 호환 확장

**REQ-IDs:** REQ-010

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`

**Interfaces:**
- Produces: `enum ComparandKind { LITERAL, PARAM }`; `StateGuard`에 nullable `String op`,
  `ComparandKind comparandKind`, `String comparand` 추가 + 기존 7/8-arg 생성자는 3필드 null 위임.

- [ ] **Step 1:** 기존 `ConstraintExtractorStateGuardTest` 전체 실행 → baseline green 확인.
- [ ] **Step 2:** `ComparandKind` enum 추가, `StateGuard` canonical에 3필드 추가,
  기존 7-arg·8-arg 생성자를 `this(..., null, null, null)` 위임 오버로드로 보존.
- [ ] **Step 3:** Run `./gradlew :graph-rag-builder:compileJava :graph-rag-builder:compileTestJava`
  + 기존 `ConstraintExtractorStateGuardTest`. Expected: 컴파일·green 불변(REQ-010).
- [ ] **Step 4:** Commit `feat(index): StateGuard에 ComparandKind+op/comparand nullable 확장 [REQ-010]`.

### Task 3: BOOLEAN 가드 검출

**REQ-IDs:** REQ-001

**Files:**
- Modify: `graph-rag-builder/.../index/ConstraintExtractor.java` (extractStateGuards)
- Modify: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/guards/StateGuards.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorStateGuardTest.java`

**Interfaces:**
- Consumes: ComparandKind (Task 2).
- Produces: `extractStateGuards`가 BOOLEAN 가드 emit (column=snake getter, op="==",
  comparandKind=LITERAL, comparand="true"/"false").

- [ ] **Step 1:** 픽스처 `StateGuards.java`에 boolean 분기 메서드 추가:
  `Booking`에 `boolean getActive()`/`boolean isActive()` 필드+getter, 메서드
  `String byActive(Booking b){ if(b.getActive()) return "on"; return "off"; }`,
  `String byNotActive(Booking b){ if(!b.isActive()) return "off"; return "on"; }`.
- [ ] **Step 2:** `ConstraintExtractorStateGuardTest#booleanGuard_truthy/_negated/_isPrefix` 작성(red):
  byActive→`kind=BOOLEAN,column="active",comparand="true"`; byNotActive→`comparand="false"`.
- [ ] **Step 3:** Run 해당 테스트 → FAIL(미검출).
- [ ] **Step 4:** `extractStateGuards`에 BOOLEAN 검출 추가: `CtIf` 조건이 (a) boolean getter
  단독 invocation, (b) `CtUnaryOperator` NOT 래핑 getter, (c) getter `==`/`!=` boolean literal일 때
  StateGuard(BOOLEAN) emit. getter는 `getterRef`(파라미터/지역 제외).
- [ ] **Step 5:** Run → PASS. 기존 StateGuard 테스트도 green.
- [ ] **Step 6:** Commit `feat(index): BOOLEAN 저장행 가드 검출(truthy/!/==) [REQ-001]`.

### Task 4: NULLITY 가드 검출

**REQ-IDs:** REQ-002

**Files:** 동 Task 3 (ConstraintExtractor, StateGuards 픽스처, 테스트)

**Interfaces:**
- Produces: NULLITY 가드 emit (op="=="/"!=", comparand="null").

- [ ] **Step 1:** 픽스처에 nullable `String getNote()` 필드+getter, 메서드
  `String byNote(Booking b){ if(b.getNote()==null) return "empty"; return "has"; }`.
- [ ] **Step 2:** `#nullityGuard_eqNull/_neNull` 작성(red): `kind=NULLITY,column="note",op="=="`.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4:** `extractStateGuards`에 NULLITY 검출: getter `==`/`!=` `null` literal → emit.
- [ ] **Step 5:** Run → PASS.
- [ ] **Step 6:** Commit `feat(index): NULLITY 저장행 가드 검출 [REQ-002]`.

### Task 5: NUMERIC-vs-상수 검출 (음수 포함)

**REQ-IDs:** REQ-003

**Files:** 동 Task 3

**Interfaces:**
- Produces: NUMERIC 가드(comparandKind=LITERAL, op∈REL_OPS, comparand=정수텍스트, 음수 언랩).

- [ ] **Step 1:** 픽스처에 int `getCount()` 필드+getter, 메서드
  `String byCount(Booking b){ if(b.getCount() > 0) return "pos"; return "nonpos"; }`,
  `String byBalance(Booking b){ if(b.getBalance() >= -5) return "ok"; return "low"; }` (int getBalance).
- [ ] **Step 2:** `#numericLiteralGuard_gt/_negativeLiteral/_floatExcluded` 작성(red):
  byCount→`op=">",comparand="0"`; byBalance→`comparand="-5"`; float 비교는 NUMERIC emit 없음.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4:** `extractStateGuards`에 NUMERIC-vs-상수 검출: getter REL_OP 정수리터럴.
  `literalLong`로 정수만, 음수는 `CtUnaryOperator`(MINUS) 언랩해 부호 반영. `addComparison`/`fieldRef` 헬퍼 재사용.
- [ ] **Step 5:** Run → PASS.
- [ ] **Step 6:** Commit `feat(index): NUMERIC-vs-상수 가드 검출(정수·음수) [REQ-003]`.

### Task 6: NUMERIC-vs-파라미터 검출

**REQ-IDs:** REQ-004

**Files:** 동 Task 3

**Interfaces:**
- Produces: NUMERIC 가드(comparandKind=PARAM, comparand=파라미터명). 직접 참조만.

- [ ] **Step 1:** 픽스처에 메서드
  `String byNightsParam(Booking b, int minNights){ if(b.getNights() >= minNights) return "ok"; return "below"; }`,
  `String byCalc(Booking b, int m){ if(b.getNights() >= m*2) return "ok"; return "below"; }` (검출 안 됨).
- [ ] **Step 2:** `#numericParamGuard_direct/_calcExcluded` 작성(red):
  byNightsParam→`comparandKind=PARAM,comparand="minNights",op=">="`; byCalc→emit 없음.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4:** NUMERIC-vs-파라미터 검출: getter REL_OP paramRef(가드 메서드 파라미터 직접 참조;
  `CtVariableRead`가 `CtParameter`). 중간 연산(BinaryOperator 등) 경유는 제외.
- [ ] **Step 5:** Run → PASS.
- [ ] **Step 6:** Commit `feat(index): NUMERIC-vs-파라미터 가드 검출(직접참조) [REQ-004]`.

### Task 7: flipValues 확장 (BOOLEAN/NULLITY/NUMERIC-상수) + 격리

**REQ-IDs:** REQ-005

**Files:**
- Modify: `graph-rag-builder/.../run/ReadInputSynthesizer.java` (flipValues)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ReadInputSynthesizerVariantTest.java`

**Interfaces:**
- Consumes: StateGuard(kind/op/comparand).
- Produces: `flipValues`가 종류별 반대-arm 값(컬럼당 1개) 반환; NOT NULL NULLITY는 빈 리스트.

- [ ] **Step 1:** `#flipBoolean/_flipNullityNonNull/_flipNullityNull/_flipNullityNotNullSkip/_flipNumericConst`
  작성(red): BOOLEAN true→[false]; NULLITY non-null→[null], null→[defaultFor]; NOT NULL→[]; `>=3`→[2].
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `flipValues`에 BOOLEAN/NULLITY/NUMERIC-상수 분기 추가(설계 §3.2 op표, 정수범위 clamp).
- [ ] **Step 4:** Run → PASS + 격리 PK 단언(`offsetPk` 적용, 변종 PK≠base) 기존 테스트 green.
- [ ] **Step 5:** Commit `feat(run): flipValues BOOLEAN/NULLITY/NUMERIC-상수 반대arm [REQ-005]`.

### Task 8: 입력-시드 공동 합성 (NUMERIC-vs-파라미터) + per-guard skip

**REQ-IDs:** REQ-006

**Files:**
- Modify: `graph-rag-builder/.../run/ReadInputSynthesizer.java` (synthesizeVariants)
- Test: `ReadInputSynthesizerVariantTest.java`

**Interfaces:**
- Produces: NUMERIC-vs-파라미터 가드 → base(col=V) + 변종(col=V±1, vbody P=V); 타깃 해소 실패 시
  해당 가드만 skip(다른 종류 변종은 유지).

- [ ] **Step 1:** `#inputSeedJoint_geParam/_collectionTargetSkipPerGuard` 작성(red):
  base 입력 minNights=V → 변종 시드 nights=V-1 + vbody minNights=V; collection에서 NUMERIC-param skip,
  BOOLEAN 변종 생성.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `synthesizeVariants`에서 NUMERIC-vs-파라미터 처리: `base.body().get(P).asLong()`로 V,
  op별 표로 시드 컬럼·vbody P 설정, offsetPk 격리. 타깃 해소 실패는 whole-skip→per-guard skip으로 변경.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(run): NUMERIC-파라미터 입력-시드 공동 합성 + per-guard skip [REQ-006]`.

### Task 9: kind별 boolean query-param gate (3-way)

**REQ-IDs:** REQ-007

**Files:**
- Modify: `graph-rag-builder/.../run/EndpointExplorationRunner.java` (exploreStateGuardVariants:816)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EndpointExplorationRunnerStateGuardTest.java` (신규)

**Interfaces:**
- Produces: gate = TEMPORAL→false, ENUM→true, BOOLEAN/NULLITY/NUMERIC→미적용.

- [ ] **Step 1:** `#gateByKind_temporalFalse/_enumTrue/_numericNoOverwrite` 작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `:816` `gate = kind != TEMPORAL`을 kind별 분기로: BOOLEAN/NULLITY/NUMERIC은
  boolean QUERY param 덮어쓰기 skip(설정 자체를 안 함), TEMPORAL/ENUM 기존 유지.
- [ ] **Step 4:** Run → PASS + 기존 delete confirm gate 회귀 green.
- [ ] **Step 5:** Commit `feat(run): exploreStateGuardVariants 3-way boolean gate [REQ-007]`.

### Task 10: Phase 1 E2E green + 회귀

**REQ-IDs:** REQ-008, REQ-009

**Files:**
- Test: `BuilderIntegrationTest.java`, 일회용 재현 테스트 제거
  (`ReproInputDrivenSeedTest.java`, `ReproInputDrivenVariantTest.java`)

- [ ] **Step 1:** Task 1의 `eligibilityNumericTwoArms` 재실행 → 이제 PASS(양 arm + 격리 시드).
- [ ] **Step 2:** 일회용 재현 테스트 2개 삭제.
- [ ] **Step 3:** Run `./gradlew :graph-rag-builder:test` 전체 → green(REQ-008 회귀 단언 포함).
- [ ] **Step 4:** Run `e2e/run-e2e.sh`(order-service) → green.
- [ ] **Step 5:** 요구사항명세 매트릭스 REQ-001~010 🟢로 갱신.
- [ ] **Step 6:** Commit `test(e2e): Phase1 eligibility 양arm green + 재현테스트 제거 [REQ-008,REQ-009]`.

> **Phase 1 종료 = PR 게이트(§리뷰/회귀/문서) 후 머지. 그 다음 Phase 2.**

---

## Phase 2 (P2) — cross-class 귀속 (후속 PR)

### Task 11: 핸들러 1-hop reachable 메서드 추출

**REQ-IDs:** REQ-011

**Files:**
- Modify/Create: `graph-rag-builder/.../index/ConstraintExtractor.java` (reachableMethods) 또는 신규 클래스
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorReachableTest.java`

**Interfaces:**
- Produces: `Set<Map.Entry<String,String>> reachableMethods(Path src, String handlerClass, String handlerMethod)`
  — 핸들러 본문 1-hop `CtInvocation`의 `(declaringTypeFqn, methodName)` + 핸들러 자신.

- [ ] **Step 1:** 픽스처(컨트롤러→서비스 위임)에 대해 `#oneHopIncludesService/_handlerSelf` 작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** 핸들러 메서드 본문 `getElements(CtInvocation)` → `getExecutable().getDeclaringType()`
  (null이면 simpleName) + `getSimpleName()` 수집.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(index): 핸들러 1-hop reachable 메서드 추출 [REQ-011]`.

### Task 12: BuilderCli cross-class 귀속 + pass-through

**REQ-IDs:** REQ-012, REQ-014

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java:702-710`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliAttributionTest.java` (신규)

**Interfaces:**
- Consumes: reachableMethods (Task 11).
- Produces: StateGuard/JoinGuard 귀속이 reachable 기반. simpleName endsWith 폴백. pass-through 매칭.

- [ ] **Step 1:** `#reachableIncludesServiceGuard/_unreachableExcluded/_joinGuardReachable/_passThrough`
  작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** 귀속 필터를 `reachableCache.computeIfAbsent(handler)` 기반
  `reachable.contains((classFqn,method)) || classFqn.endsWith("."+simpleName)`으로 확장.
  NUMERIC-vs-파라미터는 comparand가 엔드포인트 param과 동명일 때만(pass-through), 불일치 skip.
- [ ] **Step 4:** Run → PASS + 기존 order-service 귀속 회귀 green.
- [ ] **Step 5:** Commit `feat(cli): cross-class 1-hop 가드 귀속 + pass-through [REQ-012,REQ-014]`.

### Task 13: petclinic 로컬 스윕 E2E-2

**REQ-IDs:** REQ-013

**Files:**
- Create: `.work/sweep-input-driven.sh`
- Doc: 본 plan에 결과 기록

**Interfaces:**
- Produces: before/after `coveredAppBranches` 비교로 ReservationService 분기 missed→covered 확인.

- [ ] **Step 1:** `.work/sweep-input-driven.sh <before|after>` 작성: petclinic
  (`~/github_spring-petclinic/...`) src를 builder CLI에 전달해 `get-api-reservations`,
  `get-api-reservations-id` 탐색, coveredAppBranches를 `before.json`/`after.json` 저장.
- [ ] **Step 2:** Phase 2 머지 전 기준 커밋에서 `before` 실행(스크립트는 현재 HEAD로 빌드).
- [ ] **Step 3:** Phase 2 구현 후 `after` 실행, diff로 (a) list nights NUMERIC, (b) getById
  check_in_date TEMPORAL 분기 라인이 missed→covered 확인.
- [ ] **Step 4:** 요구사항명세 매트릭스 REQ-011~014 🟢로 갱신, 결과를 plan에 기록.
- [ ] **Step 5:** Commit `test(sweep): petclinic cross-class 변종 개방 검증 [REQ-013]`.

---

## Self-Review (spec coverage)

- REQ-001 BOOLEAN → Task 3 ✓ / REQ-002 NULLITY → Task 4 ✓ / REQ-003 NUMERIC-상수 → Task 5 ✓
- REQ-004 NUMERIC-param → Task 6 ✓ / REQ-005 flip → Task 7 ✓ / REQ-006 입력-시드 → Task 8 ✓
- REQ-007 gate → Task 9 ✓ / REQ-008 회귀 → Task 10 ✓ / REQ-009 E2E → Task 1+10 ✓
- REQ-010 호환 → Task 2 ✓ / REQ-011 reachable → Task 11 ✓ / REQ-012 귀속 → Task 12 ✓
- REQ-013 sweep → Task 13 ✓ / REQ-014 pass-through → Task 12 ✓
- 14/14 REQ 매핑 완료. 누락 없음.
