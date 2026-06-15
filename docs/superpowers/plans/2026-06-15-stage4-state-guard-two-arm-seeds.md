# Stage 4 — 상태 의존 가드 양-arm 시드 변종 (StateGuardOracle)

작성일: 2026-06-15 · 브랜치: `worktree-feat-concolic-twoarm-seed` (main 기준)
설계 근거: 백그라운드 워크플로 `design-concolic-twoarm-seed`(이해→접근안 3개→종합), 모든 소스 주장 verbatim 검증.

## 1. 문제

by-id 엔드포인트의 일부 분기는 **요청 입력이 아니라 저장된(시드된) 단일 행의 상태**로 갈린다.
현재 explorer는 by-id마다 **happy 행 1개만** 시드하므로 한 arm만 커버된다. order-service에서 검증된 2개:

- **GET /api/bookings/{id}** (`BookingController.java:76`):
  `if (!includeStale && b.getCheckInDate().isBefore(LocalDate.now()))` → 404 stale.
  현재 happy 시드는 `checkInDate=2037-01-01`(미래) → **false-arm(200)만 도달**, stale true-arm(404) **missed**.
  추가 게이팅: `@RequestParam(defaultValue="true") includeStale` → true-arm은 `includeStale=false`일 때만 도달.
- **DELETE /api/bookings/{id}** (`BookingController.java:115`):
  `if (b.getStatus() != PENDING && b.getStatus() != CANCELLED)` → 409 conflict.
  happy 시드의 status는 `defaultFor`가 `extractEnumColumns`의 **알파벳 TreeSet** 0번을 쓰므로 실제로는
  **CANCELLED**(부정집합 {CANCELLED,PENDING} 중 사전순 첫째) — PENDING이 아님(리뷰 정정, Opus/Sonnet/Haiku I1).
  CANCELLED도 허용집합이라 **false-arm(204)만 도달**, conflict true-arm(409) **missed**. flip = 부정집합 밖
  첫 상수 = **CONFIRMED**.
  추가 게이팅: `@RequestParam(defaultValue="false") confirm` → 409 arm은 `confirm=true` **그리고** non-PENDING/
  non-CANCELLED 행(CONFIRMED)일 때만 (confirm 미설정 시 **line 110**에서 400으로 먼저 반환).

`docs/25 §9`·Stage 3b spec이 "상태 의존 가드 양 arm"을 Stage 4로 명시 보류. 목표: 저장된 단일 행 가드의 **놓친 두 번째 arm을 결정적으로 열어**, 엔드포인트당 **서로 다른 RequiredSeed에 묶인 2개의 distinct ExploredPath**를 만들고, `exploration-report.json`에서 해당 라인이 **missed→covered**로 전이.

## 2. 접근 — 정적 StateGuardOracle → 대체 SeedRow 변종 (런타임 에이전트 없음)

종합 결론: **정적 인식 + 결정적 대체 시드값**. (in-process javaagent concolic[접근2]은 거부 — heterogeneous JDK[Java 11..23] 별도 SUT JVM에 2차 javaagent를 얹는 런타임/호환 리스크. 이 두 가드의 flip 값은 **solve가 아니라 고정 상수**라 concolic 불요.) Z3 불필요.

핵심 메커니즘:
1. **인식(정적 AST)** — `ConstraintExtractor.extractStateGuards(srcDir)` 신규. 기존 Spoon CtModel walk + `fieldRef`/`enumConstant`/`snake` 헬퍼 재사용. 두 recognizer:
   - **TEMPORAL**: `CtInvocation`이 `{isBefore,isAfter,isEqual}`, target이 getter(→컬럼), 인자가 `LocalDate.now()`/`LocalDateTime.now()` 참조.
   - **ENUM**: `extractEnumColumns`의 EQ/NE 로직을 가드로 승격(부정 enum 집합 보유).
   - **스키마 가드**: 매핑된 컬럼이 by-id 타깃 테이블의 resolved `TableSchema`에 **존재할 때만** emit. 보수적(false negative만).
2. **flip 값(결정적, clock 비커플링, Z3 없음)**:
   - TEMPORAL base 미래 = `ReadInputSynthesizer.defaultFor`의 타입별 값 재사용(`LocalDate.of(2037,1,1)` / TIMESTAMP는 2037 — **하드코딩 2999 금지**, MySQL 2038 cap).
   - TEMPORAL 대체 = `LocalDate.of(1900,1,1)` (확정 과거).
   - ENUM base = `defaultFor`가 주는 값 = 부정집합(가드 `!=` 상수들)의 알파벳 TreeSet 0번(여기선 **CANCELLED**,
     PENDING 아님 — 리뷰 정정). 대체 = **전체 enum 상수 중 부정집합 밖, 이름 사전순 첫째**(CONFIRMED), ordinal 아님.
     변종 테스트는 base를 'CANCELLED'(또는 '부정집합 원소')로, 대체를 'CONFIRMED'로 pin.
3. **변종 합성** — `ReadInputSynthesizer.synthesizeVariants(endpoint, tables, guards)` 순수 함수: `[base happy]` + 적용 가드별 클론 1개. 클론은 (i) `columns[0]=PK` 불변(Seeds.delete가 index 0 가정), (ii) **distinct offset PK**(offsetId 관례 — POST+PATH `(table,PK)` dedup에 안 먹히게), (iii) 가드 컬럼만 flip 값으로 덮어씀, (iv) FK 부모 공유/클론(parent-before-child 순서 유지). 적용 가드 없으면 singleton 반환.
4. **러너 배선** — `EndpointExplorationRunner.run()`에 `List<StateGuard> stateGuards` 파라미터 추가. `seedResource && !stateGuards.isEmpty()`이면: 변종 시드 그룹 insert + **각 변종 PK에 by-id 요청을 가드-여는 query 값 강제**(TEMPORAL→`includeStale=false`, ENUM→`confirm=true`)로 구동. 기존 `httpInvoker` probe-OR(`cumulativeCoverage`)·`CoverageFingerprint`가 두 arm을 distinct로 식별 → orchestrator dedup → `attachSeeds`가 각 path를 자기 RequiredSeed에 바인딩. SQL pass-2의 try/rollback 패턴 재사용(무익한 변종은 폐기, base 유지). stateGuards 비면 no-op.
5. **arm→seed 바인딩** — `attachSeeds`가 현재는 happy 시드 1개를 모든 path에 blanket-clone. 변종 arm path에는 그 변종의 flip 행을 바인딩하도록 확장(틀리면 생성 테스트가 잘못된 행을 재현).
6. **CLI 배선** — `BuilderCli`가 `extractStateGuards`를 1회 호출(transport-agnostic), 엔드포인트별 `handlerClass+handlerMethod` 필터 후 `runner.run`에 전달. `GRB_STATE_GUARDS` env 게이트(기본 ON, `off`로 ablation — `GRB_ORACLE` 미러).

## 3. E2E/수용 기준 (먼저 작성, double-loop 바깥 루프)

> 최고 가능 out-of-process 레벨 = `BuilderE2eTest`(실제 order-service boot jar, Docker/Postgres, `@EnabledIfSystemProperty sut.jar`). Docker 불가 환경이면 Docker-gated로 명시하고 inner 단위가 빠른 falsifiable 가드.

1. **GET 양-arm**: `get-api-bookings-id`가 **≥2 ExploredPath**, 각자 distinct non-empty RequiredSeed(`check_in_date` 1900-01-01 vs 2037-01-01), expectedStatus 합집합에 **200 AND 404** 포함. (되돌리면 200 arm만 → FAIL.)
2. **GET missed→covered**: report의 BookingController.get 핸들러에서 stale 가드 라인이 `GRB_STATE_GUARDS` on이면 `missedBranches`에 **없고**, `off` baseline이면 **있음**(귀속 가능한 전이).
3. **DELETE 양-arm**: `delete-api-bookings-id`가 status=PENDING / status=CONFIRMED 2개 RequiredSeed 그룹, distinct path, expectedStatus 합집합에 **204 AND 409**, conflict 라인 missed→covered. (`confirm=true` + CONFIRMED 행 게이팅 검증.)
4. **무회귀**: `build_exploresMultiplePathsAndCapturesBothOrms`의 기존 단언(orders 201/404/400/409, post-api-bookings 201, MyBatis 200/400, profiles SQL-seed, get-orders-id FK 시드, Kafka consumer, WS) 전부 불변.
5. **전 SUT 회귀**: `GRB_STATE_GUARDS` 기본-on으로 이전 온보딩 SUT 전체 스윕 — 인식된 가드 없는 엔드포인트는 시드 수·path 집합 불변(regression-on-sut-expansion).

## 4. Double-loop TDD 순서

1. **바깥 루프 먼저(RED, 약화 금지)**: §3의 3개 수용 단언을 `BuilderE2eTest`에 추가. 현재 한 arm만 존재 → RED.
2. **inner #1 recognizer (RED→GREEN→refactor)**: `ConstraintExtractorStateGuardTest` — BookingController 소스에서 TEMPORAL(check_in_date, line 76) + ENUM(status, 부정집합 {PENDING,CANCELLED}, line 115) 인식, pure-input `id<=0`는 미인식. GREEN: `extractStateGuards` 구현(Spoon walk + 헬퍼 재사용). refactor: EQ/NE enum 수집을 `extractEnumColumns`와 공유 헬퍼로.
3. **inner #2 변종 합성 (RED→GREEN→refactor)**: `ReadInputSynthesizerVariantTest` — fabricated bookings 스키마+2가드 → base+변종 2개, columns[0]=PK 유지, PK 충돌 없음, 가드 컬럼만 flip(1900-01-01 / CONFIRMED-by-name), base는 2037/PENDING, FK 부모 보존, 가드 컬럼 부재 시 singleton. GREEN: `synthesizeVariants` + `cloneWithColumnValue`(defaultFor 재사용). refactor: offset-PK를 offsetId와 dedupe.
4. **inner #3 러너 배선 (RED→GREEN)**: 기존 fake-CoverageClient 패턴(저장 행별 distinct 지문)으로 한 by-id 엔드포인트가 distinct RequiredSeedId 2개 path를 내고 변종 요청이 `includeStale=false`/`confirm=true`를 강제함을 단언. GREEN: 두 시드 그룹 insert + 변종별 강제 요청 + attachSeeds 확장. 빠른 테스트로 검증(Docker 아님).
5. **BuilderCli 배선** + 빌더 전 단위/통합 회귀(no Docker): 가드 없는 엔드포인트 시드 정확히 1개 불변.
6. **바깥 루프 GREEN**: `-Dsut.jar`로 `BuilderE2eTest`(Docker) → 3개 수용 통과 + `GRB_STATE_GUARDS=off` baseline로 missed→covered 델타 입증.
7. **PR 게이트**: 회귀 green(Docker-skip 명시) + `docs/25 §9`·Stage 3b 비목표 갱신(같은 브랜치) → spec-compliance 리뷰 먼저 → code-quality 리뷰(pr-review-toolkit:code-reviewer) → 모든 finding triage.

## 5. 범위 / 비범위

- **범위**: 저장된 단일 행 TEMPORAL/ENUM 상태 가드의 양-arm 시드(order-service GET stale / DELETE conflict). 정적·결정적·런타임 에이전트 없음.
- **비범위(보류)**: 집계/capacity 다중 행 가드(`COUNT(status==CONFIRMED)>=cap`) — order-service에 없고 N개 공존 행+FK 순서의 별도 row-group 메커니즘 필요. 인식 안 되는 임의 상태 가드(계산형/cross-entity/non-temporal-non-enum)는 여전히 in-process concolic 라인의 몫. `isEqual(now)` 점-술어는 v1 보류(BookingController는 isBefore만 사용) — isBefore/isAfter로 한정.

## 6. 리스크 (검증됨)

1. **DELETE confirm 게이팅(양 제안 모두 누락한 정정)**: 409 arm은 `confirm=true`(line 106 default false→111에서 400) **그리고** CONFIRMED 행일 때만. 러너가 변종 요청에 confirm=true 강제 필수.
2. **GET includeStale 게이팅**: stale 404 arm은 `includeStale=false`(line 70 default true) 필요. 과거 시드만으론 200.
3. **attachSeeds 바인딩**: 변종 flip 행을 그 지문이 만든 path에 바인딩. 틀리면 happy 행이 stale/conflict path에 붙어 fresh DB 재현 실패.
4. **recognizer 취약성(수용, 보수적)**: aliasing(`LocalDate t=now(); isBefore(t)`)·wrapper(`b.isStale()`)는 miss. false negative만 — 잘못된 변종은 절대 emit 안 함.
5. **컬럼 매핑**: getter→snake가 실제 DB 컬럼과 다르면(@Column(name=...)) 미존재 컬럼 타깃. 완화: resolved TableSchema에 있을 때만 emit.
6. **예산**: 변종당 by-id 요청 1개 추가. 가드 family당 엔드포인트당 ≤1로 cap(order-service 2 엔드포인트×1가드). `budgetRequests` 회계 재사용.
7. **결정성/엔진**: 미래 상수 2037(defaultFor 재사용, 2999 금지 — MySQL 2038), 과거 1900-01-01은 전 엔진 안전.
8. **runnability**: 수용 E2E는 Docker/Postgres+order-service jar 필요. inner 단위가 빠른 red→green, E2E는 Docker-gated 최종 게이트(infra 없으면 skip-with-reason 명시).

## 7. 관련 파일
- 신규: `index/ConstraintExtractor.java`(StateGuard record+GuardKind+extractStateGuards), `run/ReadInputSynthesizer.java`(synthesizeVariants+cloneWithColumnValue), `run/EndpointExplorationRunner.java`(stateGuards 파라미터+변종 루프+attachSeeds 확장), `cli/BuilderCli.java`(배선+GRB_STATE_GUARDS).
- 테스트: `ConstraintExtractorStateGuardTest`, `ReadInputSynthesizerVariantTest`, `BuilderE2eTest`(수용).
- 문서: `docs/25 §9`, Stage 3b spec 비목표 갱신.

## 8. 3-모델 설계 리뷰 triage (Opus/Sonnet/Haiku)

판정: Sonnet `approved_with_conditions`, Opus·Haiku `needs_revision`(주로 배선 메커니즘 미명세). 인식·값선택은
전부 소스 검증됨. located findings를 판정·반영해 **§2의 배선 메커니즘을 다음으로 확정**:

**반영(수정 완료/설계 확정):**
- **ENUM base 사실오류(Opus/Sonnet/Haiku I1) — 최우선**: base=CANCELLED(PENDING 아님), flip=CONFIRMED. §1·§2.2 정정.
- **변종 요청 구동 메커니즘(Opus I2, Sonnet I2/I3) — 핵심 갭**: 변종 요청은 orchestrator.explore() **밖**에서
  직접 `httpInvoker` 호출로 구동(orchestrator는 입력 body를 변이하므로 강제 query가 덮일 위험·mutatingById의
  resetSeeds(고정 happy.seeds) 충돌 회피). 절차: happy 탐색 종료 후, 각 변종마다 (a) 변종 시드 그룹 insert,
  (b) 고정 ObjectNode(변종 PK + `includeStale=false`/`confirm=true` 강제)로 1회 httpInvoker 호출,
  (c) 그 `InvocationOutcome`(status+coverageKey)를 추가 `PathCandidate`로 buildPaths 전에 주입. budget은
  orchestrator와 별개(엔드포인트당 +가드수 요청, order-service +2) — CLI budget 설명에 명시.
- **attachSeeds 변종→path 바인딩(Opus I3/I4, Sonnet I7) — 핵심 갭**: (1) 변종 `PathCandidate`에 **seed-group
  식별자 태그**를 달아 attachSeeds가 인덱스가 아닌 태그로 그 변종의 flip 행을 바인딩(잘못된 행 부착 방지).
  (2) GET 분기의 "첫 2xx path에만 시드" 규칙을 **상태가드 변종 path엔 완화** — 404 stale arm(비-2xx)에도 과거날짜
  변종 시드를 바인딩(현행은 비-2xx에 시드 미부착 → 수용#1 모순 해소). (3) 변종 probe가 기대 arm(404/409) 대신
  2xx 반환 시(DB race) 그 변종 path 미등록·base 유지(per-변종 granularity, Sonnet I6).
- **PK offset 소유(Opus I5, Sonnet I5)**: 변종 PK는 **synthesizeVariants가 1회 할당**(offsetId(basePk, variantIdx)).
  attachSeeds는 상태가드 변종 시드를 **재-offset 하지 않음**(insert·강제요청·RequiredSeed가 동일 PK 보장 → fresh-DB 재현).
- **fieldRef 체이닝(Opus I8, Sonnet I9)**: TEMPORAL 컬럼은 `isBefore` invocation의 **target getter**
  (`b.getCheckInDate()`)에서 `fieldRef`로 유도 — `isBefore`('is' 접두 getter 휴리스틱과 겹침)에는 적용 금지.
  인식 조건: simpleName∈{isBefore,isAfter}, 단일 인자가 `LocalDate/LocalDateTime.now()`, 컬럼=fieldRef(target).
  테스트가 인식 컬럼=check_in_date(=='before' 아님) 단언. (v1은 `isEqual` 제외 — §5.)
- **FK 부모 공유(Opus I7, Sonnet I10)**: 변종은 타깃 행만 클론, FK 부모는 **공유**(동일 부모 PK). `bookings`는
  FK 없음 → 이 경로는 두 검증 케이스에서 미사용(방어적, 검증 범위 밖 명시). fabricated FK 스키마 단위케이스로 클론 순서만 lock.
- **수용 단언 강화(Sonnet I8)**: 409 arm path의 sampleInput에 `confirm=true` 포함도 단언(게이팅 검증).
- **GRB_STATE_GUARDS off = 순수 control(Opus I6)**: off면 extractStateGuards 빈 리스트(변종 루프 skip), 그 외 무변경.
  수용#2/#3은 해당 라인이 off→missedBranches 존재 / on→부재를 단언.

**보류(근거):**
- **"메서드가 코드에 없음"(Haiku I1/I2/I4/I5/I9)**: `extractStateGuards`/`synthesizeVariants`/`attachSeeds 확장`/
  E2E 단언은 **본 작업이 신규 작성**하는 대상 — 미존재가 정상(Haiku가 계획을 "이미 존재 주장"으로 오독). 거부.
  단, 그 시그니처·배선 위치를 명세하라는 취지는 위 반영 항목으로 충족.
- **flip 1900-01-01 DBMS 안전성(Haiku I6)**: MySQL DATE 최소 1000-01-01·PG 모두 1900 유효 → 안전. 주석 1줄로 충분, 설계 변경 불요.
