# 저장 행 복합 AND 조건 변종 (StateGuard conjunction) 설계

- 작성일: 2026-06-24
- worktree/branch: `feat-stateguard-conjunction`
- 관련 컴포넌트: `graph-rag-builder` (ConstraintExtractor, ReadInputSynthesizer, BuilderCli)
- 선행: 입력-주도 시드 변종 Phase 1(#90)·Phase 2(#91) 머지됨

## 1. 배경과 문제

Phase 1/2는 저장 행의 **단일 컬럼** 가드(ENUM/NUMERIC/BOOLEAN/NULLITY/TEMPORAL)로 분기하는
arm을 변종 시드로 연다. 그러나 핸들러가 **여러 컬럼의 동시 조건**으로 분기하면:

```java
if (b.getStatus() == CONFIRMED && b.getTier() == VIP) { ... }  // 동시 만족 arm
```

각 컬럼을 **독립적으로** flip하는 현행 합성은 `status=CONFIRMED`(tier는 base) 변종과
`tier=VIP`(status는 base) 변종을 따로 만들 뿐, **두 조건을 동시에 만족하는 행**을 만들지
못해 if-true arm이 안 열린다(실측: enum 두 가드 → 합집합 N+M, 곱집합 아님). 이 동시 만족
arm이 현재 갭이다.

## 2. 목표와 비목표

### 2.1 목표
- 핸들러의 **순수 AND 복합 조건**(`A && B && …`)에서 각 leaf가 저장 행 단일 가드일 때,
  **모든 leaf를 동시에 만족하는 시드 1행**을 합성해 그 arm(if-true)을 연다.
- leaf 종류 혼합 지원: ENUM(`==C`) · NUMERIC-상수(`op 리터럴`) · BOOLEAN · NULLITY · TEMPORAL.
  (각 leaf의 만족값은 Phase 1 산출 로직 재사용.)
- cross-class: conjunction이 서비스 계층에 있어도 Phase 2 `reachable` 귀속으로 동작.
- 변종마다 `offsetPk` 격리.

### 2.2 비목표 (후속/제외)
- **양 arm**: 불만족 arm(하나라도 ≠)은 base/기존 단일 변종이 대체로 커버 — 동시 만족 arm만.
- **NUMERIC-파라미터 leaf**(`nights >= minNights`): 입력값 매칭 + 동시 만족 얽힘으로 복잡 —
  이 leaf가 섞인 조건은 통째 skip(후속).
- **OR**(`A && (B || C)`): 순수 AND만. OR 섞이면 그 조건 skip.
- **곱집합**: enum leaf가 여러 positive여도 각 leaf 한 값으로 동시 만족 1행만.
- 3개 초과 복잡 중첩, 입력 필드 conjunction(기존 `extractConjunctions`/Z3가 담당).

## 3. 설계

### 3.1 검출 — ConstraintExtractor.extractStateGuardConjunctions(srcDir) [신규]

`&&` 조건을 `flattenAnd`(기존 헬퍼)로 leaf로 분해하고, 각 leaf를 **기존 단일 가드 검출
로직**(BOOLEAN/NULLITY/NUMERIC-상수/ENUM/TEMPORAL — `booleanGuardFromCondition`,
`nullityGuardFromCondition`, NUMERIC-상수 검출, ENUM `==`, TEMPORAL 등)으로 분류한다.
저장행 leaf(`getterRef`)가 **2개 이상**이고 모두 인식되면 `StateGuardConjunction` emit:

```
record StateGuardConjunction(String classFqn, String method, int line, List<StateGuard> leaves)
```
- `leaves`는 기존 `StateGuard`(단일 컬럼 가드) 재사용 — 각 leaf의 column/kind/op/comparand 보유.
- **NUMERIC-파라미터 leaf**가 하나라도 있으면 그 조건 skip(emit 안 함).
- leaf 중 **인식 불가**(pure-input, getter 아님)가 있으면 skip(보수적 — 부분 conjunction 안 만듦).
- **OR 노드**가 조건에 섞이면(`flattenAnd`가 AND만 평탄화 — OR 만나면 leaf로 남음) 그 leaf가
  단일 가드로 인식 안 되므로 자연히 skip.
- 같은 메서드에 단일 가드도 따로 검출(기존 `extractStateGuards`)되지만, conjunction은 **2+ leaf
  동시 만족**을 추가로 여는 별도 변종이다(중복 아님 — §3.3).

### 3.2 모델

`StateGuardConjunction` record 신규. leaves는 `List<StateGuard>`. `extractStateGuards`(단일)와
독립. BuilderCli가 둘 다 추출·귀속.

### 3.3 합성 — ReadInputSynthesizer.synthesizeVariants 확장

기존 단일 가드 변종 생성 루프에 더해, conjunction 목록을 받아 conjunction마다 **변종 1개**를
추가한다:
- base target 시드 행을 복제하고, conjunction의 **각 leaf 컬럼을 그 leaf의 만족값으로 동시 설정**:
  - ENUM: `positiveConstants` 첫째(또는 enumColumns 기준 만족 상수).
  - NUMERIC-상수 `op C`: 만족 경계값(`>=C`→C, `>C`→C+1, `<=C`→C, `<C`→C-1, `==C`→C, `!=C`→C+1).
  - BOOLEAN: 트리거값(comparand). NULLITY: 만족쪽(==null→null, !=null→defaultFor). TEMPORAL: 미래(2037).
- `offsetPk`로 변종 PK 격리. FK 부모 공유(기존).
- **모순 감지**: 같은 컬럼에 leaf 2개가 상충하는 만족값(예: `status==X && status==Y`)이면 그
  conjunction skip(변종 안 만듦).
- **NOT NULL 컬럼 NULLITY-null leaf**, **타깃 테이블에 없는 컬럼** leaf → 그 conjunction skip.
- 변종은 best-effort(실패=회귀 아님).

`ReadInputSynthesizer.synthesizeVariants` 시그니처에 conjunction 인자를 추가하거나, 별도
`synthesizeConjunctionVariants`로 분리하고 호출부(`EndpointExplorationRunner.exploreStateGuardVariants`)에서
합친다. 단일 가드 변종과 동일하게 `SeedVariant`로 산출(generator 재현 경로 동일).

### 3.4 귀속 — BuilderCli (Phase 2 재사용)

`extractStateGuardConjunctions` 결과를 `reachableMethods`/`isReachable`(Phase 2)로 엔드포인트에
귀속한다. conjunction의 `classFqn`/`method`가 reachable에 속하면 그 엔드포인트의 변종 대상.
StateGuard·JoinGuard와 동일 필터 재사용.

### 3.5 gate — 기존 3-way 재사용

conjunction 변종도 boolean QUERY param gate 미적용(BOOLEAN/NULLITY/NUMERIC과 동일).

### 3.6 데이터 흐름

```
extractStateGuardConjunctions(srcDir) → allConjunctions
BuilderCli: reachable 귀속 → endpointConjunctions → runner.run(...)
EndpointExplorationRunner.exploreStateGuardVariants
  → synthesizeVariants(+ conjunctions): conjunction마다 동시 만족 시드 변종 1개(offsetPk)
  → ExploredPath(+requiredSeedIds)/RequiredSeed → generator @BeforeEach INSERT 재현
```

## 4. 컴포넌트 경계와 책임

| 단위 | 책임 | 입력 | 출력 |
|---|---|---|---|
| `extractStateGuardConjunctions` | AND 복합 조건 → 저장행 leaf 2+ conjunction | srcDir | `List<StateGuardConjunction>` |
| `synthesizeVariants`(conjunction 경로) | 모든 leaf 만족값 동시 설정한 시드 변종 1개 | endpoint, tables, conjunctions | `List<SeedVariant>` |
| `BuilderCli` 귀속 | reachable 기반 conjunction 귀속 | allConjunctions, endpoint | endpointConjunctions |

## 5. 에러 처리·엣지

- NUMERIC-파라미터 leaf 포함 → 조건 skip.
- OR 섞임 → leaf 인식 불가로 자연 skip.
- 같은 컬럼 모순 만족값 → conjunction skip.
- NOT NULL 컬럼 null leaf / 타깃 부재 컬럼 → skip.
- leaf 2개 미만(단일) → conjunction 아님(기존 단일 가드 경로).
- 변종 best-effort(실패=회귀 아님). 기존 단일 가드/TEMPORAL/ENUM 검출·합성 불변.

## 6. E2E/수용 테스트 (definition of done)

- **E2E (in-repo, order-service)**: `BookingController`에 복합 AND 분기 GET 엔드포인트 추가
  (예: `GET /api/bookings/{id}/premium-eligible` → `if (b.getStatus()==CONFIRMED && b.getTier()==VIP) return 200; else throw 404`).
  builder 탐색이 **동시 만족 시드 1행**(status=CONFIRMED & tier=VIP, 격리 PK)으로 200 arm path를
  생성함을 `BuilderIntegrationTest`로 단언. (단일 컬럼 변종만으로는 200 arm이 안 열림 — 대조.)
- **단위/통합**: `ConstraintExtractor` conjunction 검출(2 leaf 인식, NUMERIC-param/OR skip),
  `ReadInputSynthesizer` 동시 만족 변종(모든 leaf 만족값 동시 설정, 모순 skip, offsetPk),
  BuilderCli reachable 귀속(서비스 계층 conjunction).
- **회귀**: 기존 단일 가드 변종·TEMPORAL/ENUM·BuilderIntegrationTest state-guard 단언 불변 green.
- **정의**: E2E green + 단위/통합 green + 요구사항명세 추적 매트릭스 100%(Must+미연기 Should).

## 7. 테스트 전략 (inner-loop)
- 단위: 픽스처(`StateGuards.java` 또는 신규)에 복합 AND 분기 추가 → 검출·합성 단위.
- 통합: order-service E2E.

## 8. 리스크와 대안
- **R1 — leaf 만족값 산출 재사용 정확성**: Phase 1 단일 만족값 로직(enum 상수·numeric 경계·boolean·
  null·temporal)을 conjunction에 모은다. 각 leaf 독립 만족값이므로 단순 합성 — 위험 낮음.
- **R2 — 모순/충돌 조건**: 같은 컬럼 다중 leaf, 불가능 조합 → 보수적 skip(변종 미생성 > 깨진 시드).
- **R3 — 단일 가드 변종과 중복**: conjunction 변종(동시 만족)은 단일 변종(개별 flip)과 다른 행 —
  중복 아님. 단 변종 수 증가는 conjunction당 1개로 제한.
- **대안 (기각)**: 곱집합(모든 enum 조합) — 폭발, 비목표. 양 arm — 한계효용 낮음.

## 9. 변경 파일 (예상)
- `index/ConstraintExtractor.java` (StateGuardConjunction record + extractStateGuardConjunctions)
- `run/ReadInputSynthesizer.java` (synthesizeVariants conjunction 경로)
- `run/EndpointExplorationRunner.java` (conjunction 전달, 최소)
- `cli/BuilderCli.java` (conjunction 추출·reachable 귀속)
- order-service `BookingController.java` (E2E 복합 AND 엔드포인트)
- 테스트: ConstraintExtractor conjunction 테스트, ReadInputSynthesizerVariant, BuilderIntegrationTest, 픽스처
