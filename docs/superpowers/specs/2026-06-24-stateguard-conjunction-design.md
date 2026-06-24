# 저장 행 복합 AND 조건 변종 (StateGuard conjunction) 설계

- 작성일: 2026-06-24
- worktree/branch: `feat-stateguard-conjunction`
- 관련 컴포넌트: `graph-rag-builder` (ConstraintExtractor, ReadInputSynthesizer,
  EndpointExplorationRunner, BuilderCli)
- 선행: 입력-주도 시드 변종 Phase 1(#90)·Phase 2(#91) 머지됨
- 3-벤더 리뷰(Sonnet/Cursor/Gemini) findings 반영본

## 1. 배경과 문제

Phase 1/2는 저장 행의 **단일 컬럼** 가드로 분기하는 arm을 변종 시드로 연다. 그러나 핸들러가
**여러 컬럼의 동시 조건**으로 분기하면:

```java
if (b.getStatus() == CONFIRMED && b.getTier() == VIP) { ... }  // 동시 만족 arm
```

각 컬럼을 **독립적으로** flip하는 현행 합성은 `status=CONFIRMED`(tier는 base) 변종과
`tier=VIP`(status는 base) 변종을 따로 만들 뿐(실측: 합집합 N+M), **두 조건을 동시에 만족하는
행**을 못 만들어 if-true arm이 안 열린다. 이 동시 만족 arm이 갭이다.

## 2. 목표와 비목표

### 2.1 목표
- 핸들러의 **순수 AND 복합 조건**(`A && B && …`, leaf 2~3개)에서 각 leaf가 저장 행 단일 가드일 때,
  **모든 leaf를 동시에 만족하는 시드 1행**을 합성해 그 arm(if-true)을 연다.
- leaf 종류 혼합: ENUM(`==`/`!=`) · NUMERIC-상수(`op 리터럴`) · BOOLEAN · NULLITY · TEMPORAL.
- cross-class: conjunction이 서비스 계층에 있어도 Phase 2 `reachable` 귀속으로 동작.
- 변종마다 `offsetPk` 격리.

### 2.2 비목표 (후속/제외)
- **양 arm**: 불만족 arm은 base/기존 단일 변종이 대체로 커버 — 동시 만족 arm만.
- **NUMERIC-파라미터 leaf**(`nights >= minNights`): 입력 매칭+동시 만족 복잡 — 이 leaf가 섞인 조건 skip.
- **OR**(`A && (B || C)`): 순수 AND만 — OR 섞이면 skip(§3.1).
- **곱집합**: enum leaf가 여러 상수여도 각 leaf 한 만족값으로 동시 만족 1행만.
- **leaf 4개 이상** AND: skip(2~3개만 — leaf 개수 상한, AST 깊이 아님).

## 3. 설계

### 3.1 검출 — ConstraintExtractor.extractStateGuardConjunctions(srcDir) [신규]

`CtIf`/`CtConditional` 조건이 top-level `&&`(`extractConjunctions`와 동일 스캔 범위)이면
`flattenAnd`(기존)로 leaf로 분해하고, **각 leaf를 아래 순서로 분류**한다(순서 중요 — 오분류 방지):

1. **TEMPORAL** — leaf가 `getter().isBefore/isAfter(now())` invocation: `isBefore`/`isAfter` +
   `isNowCall(arg)` + `getterRef(target)`. 방향(`isBefore`|`isAfter`)을 leaf StateGuard의 `op`에
   보존(만족값 방향 결정용 — §3.3). **TEMPORAL을 BOOLEAN보다 먼저** 시도(그렇지 않으면 `isBefore`가
   is-getter로 보여 BOOLEAN "before"로 오분류됨 — Sonnet I3).
2. **BOOLEAN** — `booleanGuardFromCondition`(기존 per-leaf 헬퍼).
3. **NULLITY** — `nullityGuardFromCondition`(기존).
4. **ENUM** — leaf가 `getter() ==|!= EnumConst`: `getterRef` + `enumConstant`(EQ→positive, NE→negated).
   per-leaf 분류기 신규(기존 ENUM 검출은 전역 누적이라 leaf 단위 추출 루틴 추가).
5. **NUMERIC-상수** — `getterRef() op literalLongWithNeg`(기존 헬퍼).
6. **NUMERIC-파라미터** — `getterRef() op directParamName` → 이 leaf가 있으면 **조건 통째 skip**.
7. 그 외(pure-input, getter 아님, OR 노드 등) → **인식 불가**.

**emit 조건**(Sonnet I5): `flattenAnd` leaf 수 == 분류 성공 저장행 leaf 수 **AND** 그 수가 2~3개
**AND** NUMERIC-파라미터·인식 불가 leaf가 하나도 없을 때만 `StateGuardConjunction` emit. (부분
conjunction·OR 혼입·numeric-param 혼입은 emit 안 함 — 보수적.)

```
record StateGuardConjunction(String classFqn, String method, int line, List<StateGuard> leaves)
```
leaf는 기존 `StateGuard`(column/kind/op/comparand 보유). TEMPORAL leaf는 op에 isBefore/isAfter.

모델 빌드는 기존 `extract*` 패턴과 동일하게 독립 `buildModel`(SharedSpoonModel 통합은 후속 —
기존 미적용 메서드들과 동일, Cursor I7/Gemini I2). `GRB_STATE_GUARDS=off`이면
`extractStateGuardConjunctions`도 빈 리스트·변종 no-op(기존 state-guard ablation과 동일, Cursor I7).

### 3.2 모델 — SeedVariant 확장 (NPE 회피)

현행 `SeedVariant(SynthesizedInput input, StateGuard guard)`에 conjunction nullable 필드를 추가:
```
record SeedVariant(SynthesizedInput input, StateGuard guard, StateGuardConjunction conjunction)
```
- 단일 가드 변종: `guard != null, conjunction == null` (기존 생성자 오버로드로 후방호환).
- conjunction 변종: `guard == null, conjunction != null`.
- base: 둘 다 null.

`EndpointExplorationRunner.exploreStateGuardVariants`(현재 `variant.guard().kind()`/`.column()`을
무조건 호출 — null이면 **NPE가 best-effort try-catch에 silently 잡혀 conjunction path가 안 열림**,
3-벤더 합의 critical)를 분기:
- `variant.conjunction() != null`이면: boolean QUERY param gate **미적용**(BOOLEAN/NULLITY/NUMERIC과
  동일), `discoveredBy="state-guard-conjunction"`, tag = `state-guard-conjunction:col1+col2(+col3)`.
- 그 외 기존 단일 가드 경로(`variant.guard()` 참조)는 불변.

### 3.3 합성 — ReadInputSynthesizer (동시 만족 변종)

`synthesizeVariants`에 conjunction 목록 인자를 추가한다. **단일 가드 변종 루프 이후**,
conjunction마다 **변종 1개**를 이어 생성하며 `variantIdx`를 **연속**으로 증가시킨다(0 재시작 금지 —
offsetPk 충돌 회피, Sonnet I2):
- base target 시드 행 복제 → 각 leaf 컬럼을 `satisfyingValue(leaf, col)`로 **동시 설정**.
- 변종 PK = `offsetPk(basePk, variantIdx)`(단일 가드 변종 다음 인덱스부터). FK 부모 공유(기존).

**`satisfyingValue(StateGuard leaf, ColumnSchema col)` [신규 헬퍼]** — `flipValues`(불만족 arm)와
**별개**, if-true 만족값 산출(Sonnet I4/I6, Cursor I1/I5):

| leaf kind | 만족값 |
|---|---|
| ENUM EQ | `positiveConstants` 첫째(정렬) |
| ENUM NE | enum 상수(enumColumns/enumConstants)에서 `negatedConstants`에 없는 첫째; 없으면 leaf 인식불가→conjunction skip |
| NUMERIC-상수 `op C` | `numericParamBaseCol(op, C)`(만족 경계: >=→C, >→C+1, <=→C, <→C-1, ==→C, !=→C+1) 재사용 |
| BOOLEAN | `comparand`(트리거값) |
| NULLITY | `==null`→null, `!=null`→`defaultFor(col)`; NOT NULL 컬럼인데 null 필요 시 conjunction skip |
| TEMPORAL | `op=isBefore`→과거(1900-01-01), `op=isAfter`→미래(2037-01-01) |

**skip 조건**: 같은 컬럼에 상충 만족값(모순) / NOT NULL 컬럼 null 필요 / 타깃 테이블에 없는 컬럼 /
ENUM NE 만족값 없음 → 그 conjunction 변종 미생성. 변종 best-effort(실패=회귀 아님).

별도 메서드(`synthesizeConjunctionVariants`)로 분리할 경우에도 호출부에서 단일 가드 변종 수 N을
받아 `variantIdx`를 N부터 시작(연속). 산출은 `SeedVariant(input, null, conjunction)`.

### 3.4 귀속 — BuilderCli (Phase 2 재사용)

`extractStateGuardConjunctions` 결과를 **`allStateGuardConjunctions`**(기존 입력-필드
`allConjunctions`와 이름 구분 — Cursor I3)로 보관하고, `reachableMethods`/`isReachable`(Phase 2)로
엔드포인트에 귀속 → `endpointStateGuardConjunctions` → `runner.run(...)`. `extractConjunctions`(입력
Z3)와 `extractStateGuardConjunctions`(저장 행) **이중 추출 공존**. StateGuard·JoinGuard와 동일 필터.

### 3.5 데이터 흐름

```
extractStateGuardConjunctions(srcDir) → allStateGuardConjunctions
BuilderCli: reachable 귀속 → endpointStateGuardConjunctions → runner.run(...)
exploreStateGuardVariants → synthesizeVariants(+ conjunctions)
  → conjunction마다 동시 만족 시드 변종 1개(satisfyingValue, offsetPk 연속 idx)
  → SeedVariant(input, null, conjunction) → ExploredPath(discoveredBy="state-guard-conjunction",
     +requiredSeedIds)/RequiredSeed → generator @BeforeEach INSERT 재현
```

## 4. 컴포넌트 경계와 책임

| 단위 | 책임 | 입력 | 출력 |
|---|---|---|---|
| `extractStateGuardConjunctions` | AND 조건 → 저장행 leaf 2~3 conjunction(완전 분류 시만) | srcDir | `List<StateGuardConjunction>` |
| `satisfyingValue` | leaf kind별 if-true 만족값 | StateGuard, ColumnSchema | Object |
| `synthesizeVariants`(conjunction 경로) | 모든 leaf 만족값 동시 설정 변종 1개(연속 idx) | endpoint, tables, conjunctions | `List<SeedVariant>` |
| `exploreStateGuardVariants`(conjunction 분기) | conjunction 변종 실행(gate 미적용, tag) | variant | ExploredPath |
| `BuilderCli` 귀속 | reachable 기반 conjunction 귀속 | allStateGuardConjunctions, endpoint | endpointStateGuardConjunctions |

## 5. 에러 처리·엣지
- NUMERIC-파라미터 leaf / OR / 미인식 leaf 포함 → 조건 skip(완전 분류 시만 emit).
- 같은 컬럼 모순 만족값 / NOT NULL 컬럼 null / 타깃 부재 컬럼 / ENUM NE 만족값 없음 → conjunction skip.
- leaf 2 미만(단일)/4 이상 → conjunction 아님.
- conjunction 변종 PK는 단일 가드 변종 다음 idx부터 연속(offsetPk 충돌 회피).
- `variant.conjunction()!=null` 분기로 NPE 회피(gate 미적용).
- `GRB_STATE_GUARDS=off` → no-op.
- 변종 best-effort. 기존 단일 가드/TEMPORAL/ENUM 검출·합성·gate 불변(회귀 0).

## 6. E2E/수용 테스트 (definition of done)
- **E2E (in-repo, order-service)**: `BookingController`에 복합 AND GET 엔드포인트 추가 —
  `GET /api/bookings/{id}/premium-eligible`(endpointId=`get-api-bookings-id-premium-eligible`):
  `if (b.getStatus()==CONFIRMED && b.getTier()==VIP) return 200; else throw 404`.
  `BuilderIntegrationTest`(기존 state-guard 단언 형식, `pathsOf(asset,"get-api-bookings-id-premium-eligible")`)에서:
  conjunction 검출 → **동시 만족 시드 1행**(status=CONFIRMED & tier=VIP, 고유 PK)으로 expectedStatus=200
  arm path가 생성됨을 단언(단일 컬럼 변종만으로는 200 arm 안 열림 — 대조).
- **단위/통합**: `ConstraintExtractor` conjunction 검출(2 leaf 완전분류 / NUMERIC-param·OR·부분 skip /
  TEMPORAL 방향 op 보존), `ReadInputSynthesizer` satisfyingValue + 동시 만족 변종(모순/NOT NULL skip,
  연속 offsetPk), BuilderCli reachable 귀속(서비스 계층 conjunction).
- **회귀**: 기존 단일 가드 변종·TEMPORAL/ENUM·`BuilderIntegrationTest` state-guard 단언 불변 green.
- **정의**: E2E green + 단위/통합 green + 요구사항명세 추적 매트릭스 100%(Must+미연기 Should).
  (요구사항명세는 본 설계 다음 단계로 작성 — REQ-ID 부여 후 §6 테스트를 REQ-ID에 매핑.)

## 7. 테스트 전략 (inner-loop)
- 단위: 픽스처(`StateGuards.java` 또는 신규)에 복합 AND 분기 메서드 추가 → 검출·합성 단위.
- 통합: order-service E2E.

## 8. 리스크와 대안
- **R1 — satisfyingValue 정확성**: `flipValues`(불만족)와 **분리된** 만족값 헬퍼. ENUM NE·TEMPORAL
  방향이 핵심 — 표(§3.3)로 고정. NUMERIC은 `numericParamBaseCol` 재사용.
- **R2 — SeedVariant 모델 변경 파급**: 기존 `SeedVariant(input, guard)` 호출부(테스트 포함)는 2-arg
  오버로드로 후방호환. runner conjunction 분기만 신규.
- **R3 — 모순/충돌 조건**: 보수적 skip(변종 미생성 > 깨진 시드).
- **대안 (기각)**: 곱집합(폭발), 양 arm(한계효용 낮음), 입력-필드 conjunction과 통합(대상 다름 —
  저장행 vs 입력 Z3).

## 9. 변경 파일 (예상, graph-rag-builder 모듈 기준 전체 경로)
- `graph-rag-builder/src/main/java/io/graphrag/builder/index/ConstraintExtractor.java`
  (StateGuardConjunction record + extractStateGuardConjunctions + leaf 분류기)
- `graph-rag-builder/src/main/java/io/graphrag/builder/run/ReadInputSynthesizer.java`
  (SeedVariant conjunction 필드, satisfyingValue, synthesizeVariants conjunction 경로)
- `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`
  (exploreStateGuardVariants conjunction 분기, run 시그니처 conjunction 인자)
- `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java`
  (extractStateGuardConjunctions 호출·reachable 귀속, allStateGuardConjunctions)
- `samples/order-service/src/main/java/io/graphrag/sample/orders/BookingController.java`
  (premium-eligible 복합 AND 엔드포인트)
- 테스트: `ConstraintExtractorConjunctionTest`(신규), `ReadInputSynthesizerVariantTest`,
  `BuilderIntegrationTest`, 픽스처 `StateGuards.java`
