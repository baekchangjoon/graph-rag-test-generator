# 저장 행 복합 AND 조건 변종 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development로 task별 구현.
> 출처: design `docs/superpowers/specs/2026-06-24-stateguard-conjunction-design.md`,
> requirements `docs/superpowers/requirements/2026-06-24-stateguard-conjunction-requirements.md`

**Goal:** 핸들러의 저장행 복합 AND 조건(`A && B`)에서 모든 leaf를 동시에 만족하는 시드 1행을
합성해 if-true arm을 연다(단일 컬럼 변종으로는 못 여는 동시 만족 arm).

**Architecture:** ConstraintExtractor에 StateGuardConjunction 검출, SeedVariant에 conjunction nullable
필드 + runner 분기(NPE 회피), ReadInputSynthesizer에 satisfyingValue 동시만족 합성, BuilderCli에
reachable 귀속·ablation. Phase 1/2 단일 가드 인프라 재사용.

**Tech Stack:** Java 17, Spoon, JUnit5, AssertJ, Gradle.

## Global Constraints
- 순수 AND, 저장행 leaf 2~3개, 완전 분류 시만 emit. NUMERIC-파라미터/OR/미인식 leaf 섞이면 skip.
- satisfyingValue는 if-true 만족값(flipValues=불만족과 별개), 컬럼 JDBC 타입 변환.
- conjunction 변종 variantIdx는 단일 가드 변종 다음부터 연속(offsetPk 충돌 0).
- SeedVariant 2-arg 후방호환. runner try/catch 모두 guard null-safe.
- 기존 단일 가드/TEMPORAL/ENUM 검출·합성·gate·테스트 불변. GRB_STATE_GUARDS=off no-op.
- 커밋 author: `baekchangjoon <changjoon.baek@icloud.com>`. 변종 best-effort.

---

### Task 1: E2E 골격 — premium-eligible 엔드포인트 + 실패 E2E (red)
**REQ-IDs:** REQ-010, REQ-009(inventory)

**Files:**
- Modify: `samples/order-service/src/main/java/io/graphrag/sample/orders/BookingController.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderIntegrationTest.java`

**Interfaces:**
- Produces: `GET /api/bookings/{id}/premium-eligible`(endpointId=`get-api-bookings-id-premium-eligible`),
  `if (b.getStatus()==BookingStatus.CONFIRMED && b.getTier()==BookingTier.VIP) return 200; else 404`.

- [ ] **Step 1:** BookingController에 핸들러 추가:
```java
@GetMapping("/{id}/premium-eligible")
public BookingResponse premiumEligible(@PathVariable Long id) {
    Booking b = bookings.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not found"));
    if (b.getStatus() == BookingStatus.CONFIRMED && b.getTier() == BookingTier.VIP) {
        return toResponse(b);
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "booking " + id + " not premium-eligible");
}
```
- [ ] **Step 2:** `BuilderIntegrationTest#premiumEligibleConjunction` 작성(red): endpoint inventory
  `containsExactly`에 `get-api-bookings-id-premium-eligible` 추가. `pathsOf(asset,"get-api-bookings-id-premium-eligible")`에서
  `discoveredBy="state-guard-conjunction"` + expectedStatus=200 path가 존재하고 시드 행이 status=CONFIRMED &
  tier=VIP(동시), requiredSeedIds 비공백임을 단언.
- [ ] **Step 3:** Run `./gradlew :graph-rag-builder:test --tests "*BuilderIntegrationTest.premiumEligibleConjunction"`.
  Expected: FAIL (conjunction 미검출 → state-guard-conjunction path 없음).
- [ ] **Step 4:** Commit `test(e2e): premium-eligible 복합 AND 동시만족 E2E (red) [REQ-010]`.

### Task 2: StateGuardConjunction 검출
**REQ-IDs:** REQ-001, REQ-002

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`
- Modify: `graph-rag-builder/src/test/resources/sample-src/io/graphrag/sample/guards/StateGuards.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/index/ConstraintExtractorConjunctionTest.java` (신규)

**Interfaces:**
- Produces: `record StateGuardConjunction(String classFqn, String method, int line, List<StateGuard> leaves)`;
  `List<StateGuardConjunction> extractStateGuardConjunctions(Path srcDir)`.
- Consumes: 기존 `flattenAnd`, `booleanGuardFromCondition`, `nullityGuardFromCondition`, `getterRef`,
  `enumConstant`, `literalLongWithNeg`, `directParamName`, `isNowCall`, `REL_OPS`.

- [ ] **Step 1:** 픽스처 `StateGuards.java` `Booking`에 enum `tier`(BookingTier), boolean `active`,
  nullable `note`, int `count` 필드/getter가 없으면 추가. 복합 AND 메서드 추가:
  `byStatusTier(Booking b){ if(b.getStatus()==BookingStatus.CONFIRMED && b.getTier()==BookingTier.VIP) return "ok"; return "no"; }`,
  `byTemporalActive(Booking b){ if(b.getCheckInDate().isBefore(LocalDate.now()) && b.getActive()) return "ok"; return "no"; }`,
  `byNumParam(Booking b, int min){ if(b.getNights()>=min && b.getActive()) return "ok"; return "no"; }`(numeric-param→skip),
  `byOr(Booking b){ if(b.getActive() && (b.getCount()>0 || b.getNights()>0)) return "ok"; return "no"; }`(OR→skip),
  `byThree(Booking b){ if(b.getStatus()==BookingStatus.CONFIRMED && b.getActive() && b.getCount()>0) return "ok"; return "no"; }`.
- [ ] **Step 2:** `ConstraintExtractorConjunctionTest` 작성(red): `#detect2Leaf`(byStatusTier→leaves 2,
  status ENUM+tier ENUM), `#threeLeafEmit`(byThree→3), `#fourLeafSkip`(4-leaf 픽스처→0),
  `#temporalFirst`(byTemporalActive→check_in_date TEMPORAL op="isBefore" + active BOOLEAN, BOOLEAN 오분류 아님),
  `#numericParamSkip`(byNumParam→0), `#orSkip`(byOr→0), `#singleNotConjunction`(단일 if→0), `#ablationOff`.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4:** `extractStateGuardConjunctions` 구현: CtIf/CtConditional top-level `&&`만 `flattenAnd` →
  각 leaf를 §design 3.1 순서(TEMPORAL→BOOLEAN→NULLITY→ENUM→NUMERIC-상수→NUMERIC-param)로 분류하는
  `classifyLeaf(CtExpression)` 헬퍼. TEMPORAL leaf는 `StateGuard` op에 "isBefore"/"isAfter". leaf 수 ==
  분류 성공 저장행 leaf 수 AND 2~3개 AND numeric-param/미인식 없음일 때만 emit. `record StateGuardConjunction` 추가.
- [ ] **Step 5:** Run → PASS + 기존 ConstraintExtractor 테스트 green(회귀).
- [ ] **Step 6:** Commit `feat(index): StateGuardConjunction 검출(완전분류 AND leaf 2~3) [REQ-001,REQ-002]`.

### Task 3: SeedVariant 모델 확장 + runner NPE 회피
**REQ-IDs:** REQ-003

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java` (SeedVariant record)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (exploreStateGuardVariants)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/EndpointExplorationRunnerStateGuardTest.java`

**Interfaces:**
- Produces: `record SeedVariant(SynthesizedInput input, StateGuard guard, StateGuardConjunction conjunction)`
  + 2-arg 생성자 오버로드(`this(input, guard, null)`).

- [ ] **Step 1:** `#conjunctionCatchNpeAvoided` 작성(red): conjunction SeedVariant(guard=null)에 대해
  exploreStateGuardVariants의 gate/catch 경로가 NPE 없이 동작하는지 — gate 헬퍼 또는 식별자 추출 헬퍼를
  단위 검증(`appliesBooleanGate`처럼 conjunction 식별 헬퍼 추출 권장).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** SeedVariant에 conjunction 필드 추가 + 2-arg 오버로드. exploreStateGuardVariants에서
  `variant.conjunction()!=null` 분기: gate 미적용, `discoveredBy="state-guard-conjunction"`,
  tag/branchesTaken=`state-guard-conjunction:col1+col2`. **try 본문과 catch 로깅 모두** guard null-safe
  (식별자 헬퍼 `String variantLabel(SeedVariant)` 추출해 guard/conjunction 양쪽 처리).
- [ ] **Step 4:** Run → PASS + 기존 SeedVariant 2-arg 호출부·gate 테스트 green(후방호환).
- [ ] **Step 5:** Commit `feat(run): SeedVariant conjunction 필드 + runner NPE 회피 분기 [REQ-003]`.

### Task 4: satisfyingValue + synthesizeVariants conjunction 합성
**REQ-IDs:** REQ-004, REQ-005

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/run/ReadInputSynthesizerVariantTest.java`

**Interfaces:**
- Consumes: SeedVariant(conjunction) (Task 3), numericParamBaseCol/offsetPk/defaultFor(기존).
- Produces: `synthesizeVariants(endpoint, tables, guards, conjunctions)` — conjunction 변종 추가.

- [ ] **Step 1:** `#conjunctionSimultaneous`(status&tier 동시 만족 변종 1행, 격리 PK), `#temporalType`
  (isBefore→1900 LocalDate / TIMESTAMP→LocalDateTime), `#booleanType`(Boolean), `#numericLiteral`,
  `#variantIdxContinuous`(단일+conjunction PK 중복 0), `#sameColumnMerge`(status!=PENDING&&!=CANCELLED→
  단일 만족 상수), `#contradictionSkip`(status==X&&==Y→변종 없음) 작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `satisfyingValue(StateGuard leaf, ColumnSchema col)` 헬퍼(§design 3.3 표 — ENUM EQ/NE,
  NUMERIC=numericParamBaseCol, BOOLEAN=Boolean.valueOf(comparand), NULLITY, TEMPORAL 방향+JDBC 타입,
  op=null→isBefore). synthesizeVariants 인자에 conjunctions 추가, 단일 가드 루프 후 conjunction마다
  보정된 base(out.get(0)) 복제 → 같은컬럼 병합/모순·NOT NULL·타깃부재 skip 후 각 leaf 컬럼 satisfyingValue
  동시 설정, variantIdx 연속 offsetPk. `SeedVariant(input, null, conjunction)` 산출.
- [ ] **Step 4:** Run → PASS + 기존 변종 테스트 green.
- [ ] **Step 5:** Commit `feat(run): satisfyingValue + conjunction 동시만족 변종 합성 [REQ-004,REQ-005]`.

### Task 5: early-return·run 호출 게이트 (conjunction-only)
**REQ-IDs:** REQ-007

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java` (synthesizeVariants early-return)
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` (run 시그니처, exploreStateGuardVariants 호출 게이트)
- Test: `ReadInputSynthesizerVariantTest`, `EndpointExplorationRunnerStateGuardTest`

**Interfaces:**
- Produces: `run(..., List<StateGuard> stateGuards, List<StateGuardConjunction> stateGuardConjunctions, ...)`.

- [ ] **Step 1:** `#conjunctionOnlyNotSkipped`(synthesizeVariants: guards 빈 + conjunctions 있으면 변종 생성),
  `#conjunctionOnlyGatePasses`(run 게이트: stateGuards 빈 + conjunctions 있으면 exploreStateGuardVariants 실행) 작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** synthesizeVariants early-return을 `(guards.isEmpty() && conjunctions.isEmpty()) || base.seeds().isEmpty()`로.
  run() 시그니처에 stateGuardConjunctions 인자, 호출 게이트 `seedResource && (!stateGuards.isEmpty() ||
  !stateGuardConjunctions.isEmpty())`, exploreStateGuardVariants(...,guards,conjunctions) 단일 호출(variantIdx 연속).
  EndpointExplorationRunner의 모든 run 호출부(BuilderCli, 테스트) 시그니처 갱신.
- [ ] **Step 4:** Run → PASS + 기존 runner 테스트 green.
- [ ] **Step 5:** Commit `feat(run): conjunction-only early-return·run 게이트 [REQ-007]`.

### Task 6: BuilderCli 귀속 + ablation
**REQ-IDs:** REQ-006, REQ-008

**Files:**
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/cli/BuilderCliAttributionTest.java`

**Interfaces:**
- Consumes: extractStateGuardConjunctions(Task 2), isReachable(Phase 2), run 시그니처(Task 5).

- [ ] **Step 1:** `#conjunctionReachable`(서비스 계층 conjunction이 핸들러 1-hop 호출로 귀속), ablation 단위 작성(red).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** BuilderCli에 `allStateGuardConjunctions = GRB_STATE_GUARDS off ? List.of() :
  constraintExtractor.extractStateGuardConjunctions(config.sutSrc())`. 엔드포인트 루프에서
  `endpointStateGuardConjunctions = allStateGuardConjunctions.stream().filter(c -> isReachable(reachable, c.classFqn(), c.method())).toList()`,
  reachableCache 재사용. `runner.run(..., endpointStateGuards, endpointStateGuardConjunctions, ...)`.
- [ ] **Step 4:** Run → PASS + 기존 BuilderCliAttributionTest green.
- [ ] **Step 5:** Commit `feat(cli): conjunction reachable 귀속 + GRB ablation [REQ-006,REQ-008]`.

### Task 7: E2E green + 회귀 + 매트릭스
**REQ-IDs:** REQ-009, REQ-010

- [ ] **Step 1:** Task 1 `premiumEligibleConjunction` 재실행 → PASS(동시 만족 200 arm + 격리 시드).
- [ ] **Step 2:** Run `./gradlew :graph-rag-builder:test` 핵심(ConstraintExtractor*, ReadInputSynthesizerVariant,
  EndpointExplorationRunnerStateGuard, BuilderCliAttribution, BuilderIntegrationTest) → green(REQ-009 회귀).
- [ ] **Step 3:** 요구사항명세 매트릭스 REQ-001~010 🟢 갱신, Coverage 줄.
- [ ] **Step 4:** Commit `test(e2e): conjunction E2E green + 매트릭스 [REQ-009,REQ-010]`.

---

## Self-Review (spec coverage)
- REQ-001/002 검출 → Task 2 ✓ / REQ-003 모델·NPE → Task 3 ✓ / REQ-004/005 합성 → Task 4 ✓
- REQ-006 귀속 → Task 6 ✓ / REQ-007 게이트 → Task 5 ✓ / REQ-008 ablation → Task 6 ✓
- REQ-009 회귀 → Task 7 ✓ / REQ-010 E2E → Task 1+7 ✓
- 10/10 REQ 매핑 완료.
