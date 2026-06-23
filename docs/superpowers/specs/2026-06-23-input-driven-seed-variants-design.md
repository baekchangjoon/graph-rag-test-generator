# 입력-주도 시드 변종 (input-driven seed variants) 설계

- 작성일: 2026-06-23
- worktree/branch: `feat-input-driven-seed-variants`
- 관련 컴포넌트: `graph-rag-builder` (ConstraintExtractor, ReadInputSynthesizer,
  EndpointExplorationRunner, BuilderCli)
- 선행 재현: 본 문서 §1 (실측 완료)
- 범위 결정: 비서 승인 — 옵션 A(cross-class 포함), **2단계 PR 분할**(§2.3)

## 1. 배경과 문제 (실측 재현)

GET 엔드포인트 탐색에서, 사용자는 "조회 키(예: `id`)에 따라 저장 데이터 상태가
달라 다른 분기를 타도록, path 개수만큼 서로 다른 입력값 + 그에 맞는 격리된 시드
행이 생성되기"를 기대한다. 그러나 현재는 엔드포인트당 **단일 probe 입력 + 시드 1행**만
만들어진다.

실측 재현(petclinic `boarding` SUT, 일회용 테스트로 확인) — root cause는 **2겹**이다:

**원인 1 — 검출 범위 협소.** `ConstraintExtractor.extractStateGuards`가 검출하는
데이터-상태 분기는 `GuardKind` = `TEMPORAL`/`ENUM` **2종뿐**이다. petclinic boarding에서
검출된 StateGuard는 4개이고, GET 경로에서는 `getById`의 `check_in_date`(TEMPORAL)만
잡힌다. `list()`의 `getNights() >= minNights`(NUMERIC), `getPriceTier() == tier`(저장행 vs
파라미터 enum), `getCreatedAt().isAfter(cutoff)`(non-now) 는 **전부 누락**된다. 가드가
0개면 `ReadInputSynthesizer.synthesizeVariants`는 base만 반환(변종 0) → 시드 1행 + 입력
`{"id":"91184"}` 단일 probe.

**원인 2 — cross-class 귀속 부재.** 검출된 StateGuard는 가드가 **물리적으로 위치한
클래스/메서드**(예: `ReservationService.getById`)를 기록한다. 그러나 `BuilderCli`는
`g.classFqn().equals(endpoint.handlerClass()) && g.method().equals(endpoint.handlerMethod())`로만
엔드포인트에 귀속한다(`BuilderCli.java:702-704`). petclinic은 데이터-상태 분기가 서비스
계층에 있고 핸들러는 컨트롤러(`ReservationRestController`)라 **classFqn 불일치 → 귀속 0**.
즉 가드 종류를 늘려도 petclinic 같은 컨트롤러→서비스 **계층형 SUT에서는 변종이 안
열린다.** (반면 `order-service`는 가드가 `BookingController` 핸들러에 직접 있어 Stage 4가
동작한다 — 그래서 원인 2가 그동안 드러나지 않았다.)

두 원인 모두 해결해야 사용자가 본 petclinic 계층형 SUT에서 path가 다양화된다.

## 2. 목표와 비목표

### 2.1 목표

1. **저장값-flip 분기 검출·변종**(원인 1): 저장 행 컬럼값만 바꾸면 반대 arm이 열리는
   분기를 StateGuard로 검출·합성한다 — BOOLEAN(`if(row.getActive())`),
   NULLITY(`row.getX()==null`/`!=null`, nullable 컬럼), NUMERIC-vs-상수
   (`row.getN() OP 정수리터럴`).
2. **입력-시드 공동 합성**(원인 1, 범위 B 핵심): 비교 대상이 엔드포인트 **입력
   파라미터**인 NUMERIC 분기(`row.getNights() >= minNights`)에 대해, 입력값과 시드
   컬럼값을 **함께** 정해 양 arm을 연다.
3. **cross-class 가드 귀속**(원인 2): 핸들러 메서드가 (1-hop) 호출하는 서비스 메서드의
   StateGuard도 그 엔드포인트에 귀속해, 계층형 SUT에서 변종이 열리게 한다.
4. **격리**: 변종마다 고유 PK(`offsetPk`)로 시드를 격리한다(기존 인프라 재사용).

### 2.2 비목표 (후속 사이클)

- **커버리지 피드백 기반 시드 흔들기**(가설 H3): missed-branch 역산. 비결정 위험 — 별도.
- **관계/자식 컬렉션 분기**(`children.isEmpty()`): 자식 테이블 0/N개 시드 변종 — 별도.
- **ENUM-vs-입력 파라미터**(`getPriceTier() == tier`): 본 범위는 NUMERIC-vs-파라미터만.
  enum-vs-param은 후속(상수집합·파라미터 결합 별도 설계 필요).
- **non-now TEMPORAL**(`getCreatedAt().isAfter(cutoff)`, cutoff=계산값): now() 직접 비교가
  아닌 시간 분기 — 후속.
- **복합 다단계 조건**(여러 컬럼 동시 AND/OR): 단일 컬럼 가드만.
- **2-hop 이상 호출 추적**: cross-class 귀속은 **핸들러→직접 호출 서비스 1-hop**만
  (서비스→서비스 추가 위임은 후속).

### 2.3 2단계 PR 분할 (비서 지시)

- **Phase 1 — 가드종류확장 + 입력-시드 공동** (목표 1·2·4). E2E = `order-service`
  (가드가 컨트롤러 핸들러에 위치하므로 cross-class 없이 동작). 먼저 머지.
- **Phase 2 — cross-class 귀속** (목표 3). E2E = petclinic(귀속 해결 후 `list` nights
  변종이 열림). Phase 1 머지 후 별도 PR.

두 Phase 모두 본 설계·요구사항명세의 단일 traceability 매트릭스에 포함하되, REQ-ID에
Phase 태그를 단다.

## 3. 설계

### 3.1 검출 확장 — ConstraintExtractor (Phase 1)

`GuardKind`에 `BOOLEAN`, `NULLITY`, `NUMERIC`를 추가하고, 비교 대상 종류를 위한
`enum ComparandKind { LITERAL, PARAM }`를 추가한다. NUMERIC 표현을 위해 `StateGuard`에
비교 정보를 추가하되, **기존 record 보존 + nullable 보조 필드**를 택한다(Sonnet 리뷰 I3):

- 기존 `StateGuard`(8-arg canonical + 7-arg 호환 생성자)는 **불변 유지** — 기존
  TEMPORAL/ENUM emit·테스트가 깨지지 않는다.
- NUMERIC/BOOLEAN/NULLITY는 `StateGuard`의 신규 필드 대신, **nullable 보조 필드**를
  컴팩트 호환 생성자로 추가한다. 구체: canonical에 `op`/`comparandKind`/`comparand`
  (모두 nullable) 3필드를 더하고, **기존 7-arg·8-arg 생성자는 이 3필드를 null로 위임**하는
  오버로드로 보존한다. 이렇게 하면 기존 호출부(`new StateGuard(...TEMPORAL,null,List.of())`,
  ENUM emit)와 기존 테스트가 그대로 컴파일된다. (sealed 분리는 변종 합성 분기를 늘려
  과하므로 채택하지 않음.)

검출 규칙 — 모두 **저장행 getter**(기존 `getterRef`)만 인정(파라미터/지역변수 비교 제외):

- **BOOLEAN**: boolean 반환 getter가 `if` 조건 truthy 위치 단독(`if(b.getActive())`),
  **부정(`if(!b.getActive())`, Spoon `CtUnaryOperator` NOT — Gemini I2)**, 또는
  `== true/false`. `column=snake(getter)`, `kind=BOOLEAN`, `op="=="`,
  `comparandKind=LITERAL`, `comparand` = 가드를 트리거하는 값("true"/"false";
  부정이면 "false"). `flipValues`는 이 값의 반대를 반환(Sonnet I8).
- **NULLITY**: `getter()==null`/`!=null`. `kind=NULLITY`, `op="=="`/"!=", `comparand="null"`.
  검출은 하되, **합성은 nullable 컬럼일 때만**(NOT NULL이면 null arm 불가 → skip).
- **NUMERIC-vs-상수**: `getter() OP 정수리터럴`, OP ∈ `REL_OPS`. **정수 리터럴만**
  (`literalLong` 호환 — Double/Float 제외, Cursor I5). **음수 리터럴은 Spoon에서
  `CtUnaryOperator`(MINUS)로 표현되므로 그 래핑을 풀어 부호 반영(Gemini I1).**
  `comparandKind=LITERAL`, `comparand`=리터럴 텍스트.
- **NUMERIC-vs-파라미터**: `getter() OP paramRef`, paramRef가 **해당 가드 메서드의 파라미터**를
  직접 참조(중간 계산 경유는 제외 — 보수적, Sonnet I9). `comparandKind=PARAM`,
  `comparand`=파라미터명. **Phase 1**(가드=핸들러 메서드)에서는 파라미터가 곧 엔드포인트
  QUERY/PATH 파라미터이므로 동명. **Phase 2**(가드=서비스 메서드)에서는 핸들러가 서비스를
  pass-through로 위임(인자명 동일)한다는 가정하에 매칭하며, 이름이 다르거나 계산값을
  전달하면 **skip**(Sonnet I1, Gemini I3). 매칭 기준: `comparand` 또는 `camelToSnake(comparand)`이
  엔드포인트 QUERY/PATH 파라미터명과 일치(§3.3).

getter-vs-literal / getter-vs-param 양변 분석은 `extractComparisons`/`addComparison`의
`literalLong`·`fieldRef` 헬퍼를 재사용한다(`JoinGuard` 루프는 양변 fieldRef·리터럴 없음일
때만 emit하므로 부적합 — Cursor I5). `REL_OPS`/`FLIP` 맵을 공유한다.

### 3.2 변종 합성 확장 — ReadInputSynthesizer.flipValues (Phase 1)

종류별 반대-arm 값(컬럼당 **반대 arm 1개**로 제한 — NUMERIC cap, Cursor I10):

- **BOOLEAN**: base가 `true`면 `[false]`, 아니면 `[true]`.
- **NULLITY**: nullable에서 base가 non-null이면 `[null]`, base가 null이면 `[defaultFor(col)]`.
  NOT NULL이면 빈 리스트.
- **NUMERIC-vs-상수 C, op**: base 시드값을 한 arm으로, 반대 arm 값을 **정수 범위 보호**하에
  결정적으로 산출(예: `>=C`→true=C, false=C-1; `>C`→true=C+1, false=C; `==C`→C / C+1).
  C가 `Integer.MIN/MAX` 경계면 범위 내 결정적 대체값 선택(Sonnet I7).
- **NUMERIC-vs-파라미터**: §3.3.

`offsetPk`로 변종마다 PK 격리(기존). `offsetPk` 적용 시 `PK + VARIANT_CAP`이 타입 max를
넘으면 결정적 하향 기준값 사용(오버플로 보호, Sonnet I7).

### 3.3 입력-시드 공동 합성 (Phase 1 핵심)

`synthesizeVariants`는 이미 변종 body(`vbody`)를 조작한다(현재 PK 매핑). NUMERIC-vs-파라미터로
확장한다:

1. 비교 파라미터 `P`의 입력값 `V`를 base 입력에서 가져온다. path/query는 `synthesize`가
   `input.put(name, scalarFor(...))`로 **body ObjectNode에 flat-merge**하므로(Sonnet I4 정정),
   `base.body().get(P)`에서 추출하되 **`.asLong()`으로 숫자 변환**(노드는 TextNode이므로 —
   Gemini I5). 없으면 `scalarFor`와 동일 결정성(정수=probeId)으로 산출.
2. 변종 시드: 컬럼 `getter`에 대응하는 시드 컬럼값을 `V` 기준 결정적으로 산출(반대 arm 1개).
3. 변종 `vbody`: 비교 파라미터 `P=V`를 명시 고정(시드와 입력이 같은 기준값 `V` 공유).

op별 (base 판정 / 변종 시드 컬럼값 / vbody의 P값) 결정 규칙 — V를 기준값으로(Cursor I2):

| op | base(만족 arm) | 변종(반대 arm) 시드 col | vbody P |
|---|---|---|---|
| `>=` | col=V | col=V-1 | V |
| `>`  | col=V+1 | col=V | V |
| `<=` | col=V | col=V+1 | V |
| `<`  | col=V-1 | col=V | V |
| `==` | col=V | col=V+1 | V |
| `!=` | col=V+1 | col=V | V |

정수 경계(V±1이 타입 범위 이탈) 시 §5 결정적 대체. 결과: `(입력 P=V, 시드 col=V±1)` 쌍이
변종마다 격리 PK로 생성 → NUMERIC 분기 양 arm이 별도 path로 열린다.

**타깃 테이블 해소 보장**(Cursor I5): collection(list) 엔드포인트는 PATH param이 없어
`resolveTargetTable`의 path-string 휴리스틱에 의존한다. 해소 실패 시 NUMERIC-vs-파라미터
가드는 **skip**(변종 없음) — 잘못된 테이블 시드보다 미생성을 택함(§5).

### 3.4 변종 실행 — EndpointExplorationRunner.exploreStateGuardVariants (Phase 1)

대부분 재사용한다. 단 **boolean query-param gate**(`:816` `gate = kind != TEMPORAL`)를
종류별로 분기한다(Sonnet I1, Cursor I8) — 신규 종류가 무관한 boolean QUERY param(`includeStale`,
`confirm`)을 오염시키지 않도록:

| GuardKind | boolean QUERY param gate |
|---|---|
| TEMPORAL | `false` (기존 유지) |
| ENUM | `true` (기존 유지) |
| BOOLEAN / NULLITY / NUMERIC | **미적용** (가드 컬럼은 시드로 flip되고, NUMERIC-vs-param은 §3.3에서 vbody에 직접 설정하므로 무관 param 덮어쓰기 금지) |

### 3.5 cross-class 가드 귀속 (Phase 2)

핸들러 메서드(`endpoint.handlerClass#handlerMethod`)가 **직접 호출하는** 메서드 집합
`reachable = {(declaringTypeFqn, methodName)}`을 Spoon으로 1-hop 추출한다(핸들러 본문의
`CtInvocation` 순회 → `getExecutable().getDeclaringType()` + `getSimpleName()`). 핸들러
자신도 포함.

`BuilderCli`의 귀속 필터를 확장:
```
// 현재: g.classFqn()==handlerClass && g.method()==handlerMethod
// 변경: reachable(endpoint).contains( (g.classFqn(), g.method()) )
```
JoinGuard에도 동일 적용(일관성).

**캐시**(Sonnet I3): `extractStateGuards` 등 기존 extract*는 호출마다 Spoon `buildModel()`을
한다. `reachableMethods`도 같으면 엔드포인트 N개 = N회 빌드. 따라서 `BuilderCli`에서
`Map<String, Set<Pair>> reachableCache`(키=`handlerClass#handlerMethod`)에 `computeIfAbsent`로
보관해 **고유 핸들러당 Spoon 추출 1회**만 발생시킨다(전체 모델 1회 빌드 후 핸들러별 조회가 더
나으면 그 방식 채택).

**보수성·폴백**: 1-hop만(서비스→서비스 추가 위임 제외). `noClasspath=true`에서 `CtInvocation`의
`getExecutable().getDeclaringType()`이 인터페이스 FQN을 줄 수 있으므로, StateGuard의 구현 클래스
FQN과 매칭 시 `g.classFqn().endsWith("." + ref.getSimpleName())` 폴백을 쓴다
(`RouterFunctionIndexer`의 `getSimpleName()` 비교 선례 참조). 동명 클래스가 두 패키지에 있으면
과귀속 가능 → §8 R3 완화(변종=best-effort, 실패=회귀 아님)로 수용. 매칭 실패 시 기존처럼
귀속 0(회귀 안전).

**파라미터 pass-through**(Gemini I3, Sonnet I1): Phase 2에서 NUMERIC-vs-파라미터 가드의
`comparand`(서비스 파라미터명)는 핸들러가 `service.list(minNights)`처럼 **동명 인자로
pass-through**한다는 가정하에 엔드포인트 param과 매칭한다. 인자명이 다르거나 계산값을
전달하면 skip(§3.1). (CtInvocation 인자→파라미터 1-hop 매핑은 후속 강화.)

### 3.6 데이터 흐름

```
정적분석:
  extractStateGuards (확장 GuardKind)  → allStateGuards (위치=서비스/컨트롤러)
  reachableMethods(endpoint) (Phase 2) → 핸들러 1-hop 호출 집합
BuilderCli:
  endpointStateGuards = allStateGuards.filter(reachable.contains(class,method))  // Phase 2 확장
run() → exploreStateGuardVariants
  → synthesizeVariants: 가드별 flipValues + 입력-시드 공동(vbody) + offsetPk 격리
  → 변종 시드 INSERT + 입력 invoke → ExploredPath(+requiredSeedIds)/RequiredSeed(pathId)
attachSeeds → GraphAsset → generator: seedsForPath → @BeforeEach INSERT / @AfterEach DELETE
```

## 4. 컴포넌트 경계와 책임

| 단위 | 책임 | 입력 | 출력 |
|---|---|---|---|
| `ConstraintExtractor.extractStateGuards` | 데이터-상태 분기 → StateGuard(kind/op/comparand) | srcDir | `List<StateGuard>` |
| `ConstraintExtractor.reachableMethods` (신규, Phase 2) | 핸들러 1-hop 호출 메서드 집합 | srcDir, handlerClass, handlerMethod | `Set<(fqn,method)>` |
| `ReadInputSynthesizer.flipValues` | 가드별 반대-arm 컬럼값 | guard, col, baseState | `List<Object>` |
| `ReadInputSynthesizer.synthesizeVariants` | base+변종(입력+시드 공동, 격리 PK) | endpoint, tables, guards | `List<SeedVariant>` |
| `BuilderCli` (귀속, Phase 2) | reachable 기반 가드 귀속 | allStateGuards, endpoint | endpointStateGuards |
| `EndpointExplorationRunner.exploreStateGuardVariants` | 변종 실행·path/seed 생성, kind별 gate | endpoint, tables, guards | `VariantResult` |

## 5. 에러 처리·엣지

- **NOT NULL 컬럼 NULLITY**: null arm 불가 → 합성 skip(검출은 유지).
- **컬럼 스키마 부재**: 가드 컬럼이 타깃 테이블에 없으면 skip(기존).
- **타깃 테이블 해소 실패**(collection 엔드포인트): NUMERIC-vs-파라미터 가드 skip.
- **파라미터 매칭 실패**: NUMERIC-vs-파라미터에서 comparand가 엔드포인트 param과 매칭
  안 되면 **skip**(강등 없음 — Cursor I11).
- **정수 오버/언더플로**(Sonnet I7, Cursor I8): `offsetPk(base, idx)`에서 `base + CAP`가
  타입 max 초과면 결정적 하향 기준 `max - (CAP - idx)` 사용. NUMERIC 경계(`V±1`/`C±1`)도
  `Integer.MIN/MAX` 근처면 범위 내 결정적 대체값 선택. 둘 다 silent 오작동 금지.
- **변종 폭발**: 컬럼당 `VARIANT_CAP`(4) 적용. NUMERIC/BOOLEAN/NULLITY는 컬럼당 반대-arm 1개.
- **cross-class 모호성**(Phase 2): declaringType 미해소 → simple-name 폴백, 그래도 실패 시
  귀속 0(회귀 안전).
- **회귀 안전**: 기존 TEMPORAL/ENUM 검출·합성·귀속·테스트 불변. 신규는 추가만.

## 6. E2E/수용 테스트 (definition of done)

최고 가능 out-of-process 수준 = builder e2e harness + `BuilderIntegrationTest`(in-repo,
order-service)이며, petclinic은 외부 경로(`~/github_spring-petclinic`)라 **로컬 스윕**으로
다룬다(Sonnet I2, Cursor I1/I4).

### Phase 1 (가드종류확장 + 입력-시드 공동)
- **E2E-1a (검출·합성 정확성, 통합)**: 픽스처 `sample-src/.../StateGuards.java`에
  BOOLEAN/NULLITY/NUMERIC-상수/NUMERIC-파라미터 분기 메서드를 추가하고,
  `extractStateGuards`가 각 GuardKind를 올바른 `column`/`op`/`comparand`로 검출하며,
  `synthesizeVariants`가 base + 반대-arm 변종(격리 PK)을 생성. (각 케이스의 기대값은
  요구사항명세 수용기준 표로 고정.)
- **E2E-1b (in-repo 동작, order-service)**: `BookingController`(핸들러가 entity를 직접
  조회 → cross-class 불필요)에 새 GET 엔드포인트 `GET /api/bookings/{id}/eligibility?minNights={n}`를
  추가하고, 핸들러 본문에 **`b.getNights() >= minNights`(NUMERIC-vs-파라미터)** 분기를 둔다
  (선택적으로 `b.getLoyaltyPoints() > 0`(NUMERIC-vs-상수)도). `BuilderIntegrationTest`에서:
  (1) `extractStateGuards`가 `nights` NUMERIC 가드를 `op=">="`, `comparandKind=PARAM`,
  `comparand="minNights"`로 검출, (2) 탐색 결과 그 분기의 **양 arm path**가 생성되고 각 path가
  `nights=minNights±1` 격리 시드(고유 PK)와 `minNights=V` 입력을 가짐을 단언.
  성공 지표 = 양 arm path 존재 + path별 `requiredSeedIds` 격리.

### Phase 2 (cross-class 귀속)
- **E2E-2 (계층형 SUT)**: cross-class 귀속은 **모든** StateGuard 종류에 적용되므로 petclinic의
  두 케이스를 함께 검증한다 — (a) `GET /api/reservations`(`ReservationService.list`의
  `getNights() >= minNights`, NUMERIC) 변종 path 개방, (b) `GET /api/reservations/{id}`
  (`ReservationService.getById`의 `check_in_date` TEMPORAL, 기존엔 cross-class 실패로
  안 열리던 것)의 stale arm 개방(Cursor I7). 성공 지표 = `ExplorationReport.coveredAppBranches`의
  `ReservationService` 해당 분기 라인 missed→covered(핸들러-level `missedBranches` 아님).
- **로컬 스윕 runbook**(Cursor I6): petclinic은 외부 경로(`~/github_spring-petclinic`)라
  CI 밖. `.work/` 스크립트로 builder CLI에 petclinic src/실행을 넘겨 탐색하고, 위 endpointId
  (`get-api-reservations`, `get-api-reservations-id`)의 covered app-branch를 before/after
  비교한다. (정확한 CLI 인자·경로는 plan에서 고정.)

### 회귀 (필수, in-repo)
- `BuilderIntegrationTest`의 기존 state-guard 단언(`get-api-bookings-id` TEMPORAL 404 arm,
  `delete-api-bookings-id` ENUM 409 arm 등) 불변(Cursor I4).
- `./gradlew :graph-rag-builder:test` 및 `e2e/run-e2e.sh`(order-service) green.

정의: Phase별 E2E green + 단위/통합 green + 요구사항명세 추적 매트릭스 100%(Must+미연기 Should).

## 7. 테스트 전략 (inner-loop)

- 단위: `ConstraintExtractorStateGuardTest`에 BOOLEAN/NULLITY/NUMERIC(상수·파라미터,
  직접참조 검출 / 중간계산 skip) 케이스 추가. `ReadInputSynthesizerVariantTest`에 종류별
  flipValues/입력-시드 공동/격리 PK/타깃 해소 실패 skip 케이스. (Phase 2) reachableMethods
  1-hop 추출 + BuilderCli 귀속 단위.
- 통합: 픽스처 소스 → extract → synthesizeVariants 연동(E2E-1a). order-service
  BuilderIntegrationTest(E2E-1b). petclinic 로컬 스윕(E2E-2).

## 8. 리스크와 대안

- **R1 — NUMERIC-vs-파라미터 검출 정밀도**: 중간 계산 경유 시 추적 난이. 완화: 직접 참조만
  보수적 검출, 그 외 skip(미검출 > 오검출, 회귀 0 우선).
- **R2 — 입력-시드 기준값 불일치**: 입력 V와 시드 경계 어긋나면 arm 미개방. 완화: 시드를 V
  기준 ±1 결정적 산출, V를 vbody에 명시 고정.
- **R3 — cross-class 과귀속**(Phase 2): 1-hop이 너무 많은 가드를 끌어와 무관 변종 생성.
  완화: 1-hop 한정 + 가드 컬럼이 타깃 테이블에 있을 때만 합성(기존 컬럼 가드). 변종은
  여전히 best-effort(실패=회귀 아님).
- **R4 — petclinic 외부 의존**: in-repo CI에서 못 돌림. 완화: 필수 게이트는
  `BuilderIntegrationTest`+`./gradlew test`+`e2e/run-e2e.sh`(order-service), petclinic은
  로컬 스윕으로 분리.
- **R5 — BOOLEAN-param 의미 연동**(Sonnet I5): 저장행 boolean 컬럼이 boolean QUERY
  param과 의미상 연동된 경우(`includeInactive` ↔ `active`) gate 설정이 효과적일 수 있으나,
  본 범위는 보수적으로 gate 미적용(오염 위험 > 미개방 위험). 명시적 BOOLEAN-param 연동
  검출은 후속.
- **대안 (기각) H3 커버리지 피드백**: 더 일반적이나 비결정·역산 난도 — 후속.

## 9. 변경 파일 (예상)

Phase 1:
- `index/ConstraintExtractor.java` (GuardKind/StateGuard 호환 확장, BOOLEAN/NULLITY/NUMERIC 검출)
- `run/ReadInputSynthesizer.java` (flipValues/synthesizeVariants 입력-시드 공동)
- `run/EndpointExplorationRunner.java` (kind별 boolean gate)
- `samples/order-service/.../BookingController.java`: `GET /api/bookings/{id}/eligibility`
  엔드포인트 + `b.getNights() >= minNights` 분기 추가(E2E-1b)
- 테스트: `ConstraintExtractorStateGuardTest`, `ReadInputSynthesizerVariantTest`,
  `StateGuards.java` 픽스처, `BuilderIntegrationTest`

Phase 2:
- `index/ConstraintExtractor.java` 또는 신규 (reachableMethods 1-hop)
- `cli/BuilderCli.java` (reachable 기반 귀속 필터)
- 테스트: reachable 단위 + petclinic 로컬 스윕 문서/스크립트
