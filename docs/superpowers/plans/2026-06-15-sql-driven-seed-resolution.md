# 구현 계획: SQL-기반 시드 타깃 해석 (2-pass 보정 탐색)

- 작성일: 2026-06-15 (v2 — 3-모델 리뷰 반영)
- 선행 스펙: `docs/superpowers/specs/2026-06-15-seed-target-resolution-gap.md`
- 결정: 사용자 선택 = **approach 1 (2-pass 탐색, 캡처 SQL 기반)**
- 브랜치/워크트리: `worktree-feat-sql-driven-seed-resolution`
- 관련 메모리: `input-discovery-staged-roadmap`, `regression-on-sut-expansion`, `coverage-handler-class-scoping`

---

## 0. 코드 확인으로 갱신된 사실 (브리핑 대비 정정)

| 케이스 | 실제 read 경로 (코드 근거) | SQL 시드로 의미있는 200? |
|---|---|---|
| analytics `getUserMood` `/internal/analytics/mood/{userId}` | `findByUserIdOrderByOccurredAtAsc(userId)` → WHERE `user_id`(비-PK). `mood_point` 스칼라 컬럼만. **시드 없어도 빈 리스트로 이미 200**. | ✅ 빈 200 → 데이터 200(DTO 매핑·avg 분기 커버) |
| analytics `getGlobal` `/internal/analytics/global` | `countAll()`+`countGroupedBySource()`. path 변수 없음, WHERE 없음. | ✅ FROM `mood_point` 시드 → 집계 분기 커버 |
| mindgraph `byDiary` `/internal/graphs/diary/{diaryId}` | `findById(diaryId)`(PK) → `toMindGraph` → `objectMapper.readValue(nodes_json …)` | ⚠️ 해석/시드는 2-pass로 됨. `nodes_json`/`links_json`(text)을 "probe"로 시드 시 역직렬화 **500**. 200은 **Step 5(JSON 콘텐츠) 전제** |
| mindgraph `byUser` `/internal/graphs/user/{userId}` | `getLatestGraphByUserId` → `cache.getLatest`(**Redis만**, DB 미접근, orElseThrow) | ❌ **SQL 시드 영구 불가**(pass-1에 SELECT 없음 → hint=null). **범위 밖** |
| notification | Redis 백엔드 | ❌ 범위 밖(후순위) |

→ 브리핑 수용기준 1의 "byUser 비-PK SQL 시드→200"은 **byUser=Redis라 실현 불가**(정정). **analytics가 1차 수용 대상.** mindgraph byDiary는 해석+시드까지가 본 작업 보장, 200은 Step 5 승인 시.

---

## 1. 목표 (한 줄)

탐색이 캡처한 SELECT의 **`tableName()`(FROM) → 시드 테이블**, **WHERE `col=?` 바인딩(컬럼명=param의 snake형, 또는 바인딩값=보낸 param값) → 그 param이 시드할 컬럼**을 근거로, path-string 휴리스틱이 놓친 엔드포인트를 **보정형 2-pass**(1차 탐색으로 SQL 관측 → hint로 보정 시드 → 2차 탐색)로 시드한다.

---

## 2. 설계

### 2.1 보정형(lazy) 2-pass — runner 흐름

`EndpointExplorationRunner.run()` 의 read-path/by-id 엔드포인트에서:

1. **Pass 1 (현행 그대로)**: 휴리스틱 시드(`ReadInputSynthesizer` 무-hint) → `orchestrator.explore` → path별 `captureSql` 누적(`allSql`). community/petclinic/order 는 여기서 이미 올바르게 시드·200.
2. **hint 도출**: pass-1 `allSql`(전체 path 합산) 에서 `SqlSeedResolver.resolve(...)` 로 `ResolutionHint` 산출(§2.2).
3. **pass-2 필요 판정 (게이트: 휴리스틱이 테이블 미해석일 때만)** — v3 정정(전 SUT 스윕 후):
   ```
   ResolutionHint heuristic = readSynth.heuristicResolution(endpoint, tables);
   ResolutionHint hint = (heuristic.table() == null)
       ? SqlSeedResolver.resolve(allSql, sentParamValues, endpoint, tables) : null;
   boolean needsPass2 = hint != null && hint.table() != null;
   ```
   - **핵심 게이트 `heuristic.table()==null`**: 휴리스틱이 이미 테이블을 해석한 엔드포인트
     (petclinic `/pets`→`pets`, community `/posts`→`post`, order `/orders`→`orders`)는 **재탐색을
     아예 하지 않는다** → baseline과 byte-identical → **회귀 0**(증명 가능).
   - 게이트가 필요한 2가지 실증 근거(8개 SUT 스윕에서 관측):
     1. **다중 SELECT 오선택**: petclinic `GET /api/pets/{petId}`는 부모 `pets`와 자식 컬렉션
        `visits`(by `pet_id`)를 모두 SELECT. param명 `petId`가 자식 FK `pet_id`와 이름 매칭돼 리졸버가
        **`visits` 오선택**. 휴리스틱은 `pets`를 맞히므로 게이트로 차단.
     2. **재탐색 측정 아티팩트**: community `get-internal-posts-id`의 post SELECT는 바인딩이 없어
        hint=(post,**빈 paramColumn**)≠heuristic으로 트리거되나 시드는 동일(빈 맵→PK 폴백). 그럼에도
        재탐색+cumulative 리셋만으로 측정 line이 62→79%로 흔들림(↑/↓ 가능). 동작하던 엔드포인트의
        측정 불안정 = 회귀 위험 → 게이트로 재탐색 자체를 차단.
   - 실제 타깃(analytics/mindgraph/diary/auth-user)은 모두 resource명≠table명 → `heuristic.table()==null`
     → 게이트 통과 → 정상 보정. **게이트는 타깃을 하나도 놓치지 않는다.**
4. **Pass 2 (보정, 조건부)** — needsPass2일 때만, 아래 순서로:
   ```
   1) DELETE pass-1 seeds (happy.seeds() 역순 — child→parent)        // pass-1 시드 정리(있었다면)
   2) happy2 = new ReadInputSynthesizer(...).synthesize(endpoint, tables, hint)
   3) for each seed in happy2.seeds(): Seeds.insert; resyncIdentitySequence(seed.table)
   4) coverage.dump(true)                                            // 부팅+시드 구간 컷, baseline
   5) cumulativeCoverage = new ExecutionDataStore()                  // 리포트를 시드된 run만 반영
   6) outcome = orchestrator.explore(target2)                        // target2는 happy2.body() 기반
   7) captureSql/paths 재생성, 그리고 §2.4 seed→path 연결 블록을 happy2 기준으로 실행
   ```
   - **방어**: 3)의 INSERT가 실패(hint.table 부정합 등)하면 예외를 잡아 pass-2를 폐기하고 **pass-1 결과로 폴백**(로그 남김). hint.table은 §2.2에서 스키마 존재 테이블만 선정하므로 정상 경로에선 발생하지 않음.

### 2.2 `SqlSeedResolver.resolve(...)` (신규) — `ResolutionHint` 도출

- **입력**: `List<CapturedSql> pass1Sql`(전체 path 합산), `Map<String,String> sentParamValues`(=happy.body()의 PATH/QUERY 필드명→문자열값), `Endpoint endpoint`, `List<TableSchema> tables`.
- **API**: `CapturedSql.sqlKind()`, `CapturedSql.tableName()`(FROM 테이블, 이미 파싱됨), `CapturedSql.bindings()`; 바인딩별 `SqlBinding.column()`/`value()`/`origin()`/`table()`.
- **후보 SELECT**: `sqlKind().equals("SELECT")` && `tableName()` 가 어떤 `TableSchema.name()` 과 일치하는 것.
- **table 선정**:
  1. 후보 중 "param 매칭 바인딩"(아래 paramColumn 규칙으로 컬럼이 잡히는 바인딩)을 가진 SELECT 우선(= 조회 주체).
  2. 없으면(getGlobal: WHERE 없음) `tableName()`이 스키마에 있는 **첫 후보 SELECT**(stream `findFirst`, allSql 등장 순).
- **paramColumn**: 각 PATH/QUERY param에 대해 선정 SELECT의 바인딩에서 컬럼을 1개 고른다.
  1. **1순위(컬럼명 매칭)**: `SqlBinding.column().equalsIgnoreCase(camelToSnake(param.name()))` 인 바인딩의 컬럼. (analytics `userId`→`user_id`, mindgraph `diaryId`→`diary_id` 모두 여기서 해결 — 결정적·충돌없음)
  2. **2순위(값 매칭)**: 1순위 실패 시, `origin()==API_PARAM && value().equals(sentParamValues.get(param.name()))` 인 바인딩의 컬럼. 동일 값 바인딩 복수면 **선정 SELECT 내 첫 occurrence**.
  3. 둘 다 실패 → 그 param은 미매핑(맵에 미포함). `synthesize`에서 휴리스틱 폴백.
- **산출**: `record ResolutionHint(String table, Map<String,String> paramColumn)`. 후보 SELECT 없음(byUser/notification) → `null`.

### 2.3 `ReadInputSynthesizer` hint 오버로드

- 신규 `synthesize(Endpoint, List<TableSchema>, ResolutionHint hint)`. 기존 `synthesize(e, tables)` 는 `synthesize(e, tables, null)` 위임(**기존 동작·테스트 불변**).
- `resolveTargetTable`: `hint!=null && hint.table()!=null` → `tables.stream().filter(t->t.name().equals(hint.table())).findFirst()`. 아니면 현행 휴리스틱.
- `mapParamToColumn`: `hint!=null && hint.paramColumn().containsKey(param.name())` → 그 컬럼. 아니면 현행(PATH=PK, QUERY=snake 일치).
- **타입 강제는 기존 경로 그대로**: param 루프(현 76–96행)에서 컬럼 결정 후 `coerceForColumn(scalarFor(param), column, target)` 가 컬럼 JDBC 타입으로 변환. 비-PK varchar `user_id` → 문자열 유지, 정수 컬럼이면 정수 변환(파싱 실패 시 문자열 유지 — 현행 동작). PK는 `keyProbe`로 채워짐(현행).
- pass1Target/heuristicParamColumns 노출: §2.1-3 판정을 위해, hint 없이 한 번 `resolveTargetTable`/`mapParamToColumn`을 호출해 얻거나(순수 함수, 부작용 없음), 해당 메서드를 package-private로 열어 runner가 조회.

### 2.4 runner seed→path 연결 블록 재사용 (핵심 정정)

pass-1에서 analytics/mindgraph는 `resolveTargetTable`=null → `happy.seeds()` 비어 `requiredSeeds` 빈 채 → 현재 `run()`의 **seed→path 연결 블록(현 215–279행: readPath 성공-path 링크 + `withSeedIds`)이 통째로 스킵**된다. 따라서 pass-2는 이 블록을 "재계산"이 아니라 **신규 실행**해야 한다.

- 이 블록(seed INSERT/resync·`RequiredSeed` 구성·첫 2xx path 탐색·seedId 연결)을 **private 헬퍼로 추출**하여 pass-1/pass-2가 공용한다(중복 제거). 시그니처(개략): `List<RequiredSeed> attachSeeds(Endpoint, List<ExploredPath>, List<RequiredSeed> baseSeeds, boolean readPath, ...)` → 갱신된 paths/requiredSeeds 반환.
- `mutatingById`는 본 작업 대상(GET read)에서 항상 false(`!readPath && hasPathParam`)이므로 pass-2에서 신경 쓸 필요 없음 — 단, 헬퍼 추출 시 비-GET by-id의 기존 동작은 보존(현 231–278행 분기 유지).

---

## 3. E2E / 수용 기준 (double-loop 외부 루프) — falsifiable

커버리지 분모: 메모리 `coverage-handler-class-scoping` 대로 **handler 클래스 기준 line/branch**(graph.json/로그의 'exploration coverage' 값). 목표 수치는 1회 측정 후 **핀(pin)** 하여 회귀 가드로 쓴다.

1. **(1차·필수) analytics** — `.work/run-msa-builder.sh analytics`:
   - graph.json: `getUserMood` 의 첫 2xx path `requiredSeedIds` 가 **비어있지 않고**, 그 seed의 `table=="mood_point"` 이며 `columns` 에 `user_id` 포함.
   - `getGlobal`: 집계 핸들러 분기의 `BranchRef` 가 `coveredAppBranches` 에 포함(빈→비빈 데이터 차이로 분기 커버).
   - 탐색 line 커버리지 12% → **측정 후 핀값 이상**(예상 ≥ 35%; 실제값 측정 기록).
2. **(2차·Step 5 승인 조건부) mindgraph** — `.work/run-msa-builder.sh mindgraph`:
   - `byDiary` 첫 path seed `table=="graph_record"`, `columns` 에 `diary_id` 포함(= 해석+시드 성공, **Step 5 없이도 보장**).
   - **Step 5 승인 시**: `byDiary` path `status=200`(역직렬화 통과), 탐색 line 5% → 측정 후 핀값 이상(예상 ≥ 30%).
   - **Step 5 미승인 시 종착 상태(허용된 완료)**: byDiary 해석+시드 성공이 graph.json seed로 확인되고, 응답은 500으로 **명시 기록**. 이는 본 계획의 정상 종료 상태로 간주.
   - `byUser` 는 Redis라 404 유지 — 범위 밖으로 명시 기록.
   - (참고: spec §5의 mindgraph ≥40% 목표는 byUser 포함 전제였음. byUser=Redis 제외로 본 계획은 byDiary 단독 기준으로 하향, 핀값은 측정 기록.)
3. **(회귀·필수)** order-service e2e **45/45 유지**; petclinic·community 빌더 재실행 시 기존 커버리지 동급 이상(2-pass needsPass2=false 확인 = no-op).
4. **(단위·필수)** 신규/수정 단위테스트 GREEN(§4).

> 외부 루프 한계: analytics(JDK23+PG+Kafka)·mindgraph(JDK11+PG+Redis+Kafka) 컨테이너 필요로 느림 → CI 아닌 로컬 수동. **단, analytics는 본 계획 완료 전 최소 1회 실제 구동하여 위 1번을 실측·기록한다**(단위만 GREEN으로 완료 선언 금지).

### 3.1 결정적 단위/통합 재현 (외부 루프의 결정적 대체 + 내부 루프)

- **`SqlSeedResolverTest`** (순수 단위; 외부 파일 없이 `CapturedSql`/`SqlBinding` 직접 생성):
  - 예시 픽스처(analytics getUserMood): `new CapturedSql("sql-1","p1","SELECT","select … from mood_point where user_id=?","mood_point", List.of(new SqlBinding(1,"user_id","probe-userId-90042",API_PARAM,"mood_point")))`, sentParamValues={userId→"probe-userId-90042"} → hint=(table="mood_point",{userId→"user_id"}).
  - 케이스: ① 컬럼명 매칭(userId→user_id), ② 값 매칭 폴백(컬럼명 불일치+값일치), ③ getGlobal(`select count(*) from mood_point`, WHERE 없음 → table=mood_point, paramColumn 빈맵), ④ mindgraph(graph_record/diary_id), ⑤ byUser(SELECT 없음 → null), ⑥ 동일 값 복수 바인딩 tie-break(첫 occurrence), ⑦ FROM 테이블이 스키마에 없음 → 그 SELECT 제외.
- **`ReadInputSynthesizerHintTest`** (순수 단위):
  - ① hint=null → 기존 동작 동일(회귀), ② hint=(mood_point,{userId→user_id}) → user_id 컬럼에 probe값(문자열) 시드, PK `id`는 keyProbe, ③ hint=(graph_record,{diaryId→diary_id}) → diary_id(PK) 시드, ④ hint=(mood_point, 빈맵)=getGlobal → PK+NOT NULL만 시드, ⑤ 정수 비-PK 컬럼에 hint 매핑 시 coerceForColumn 정수 변환 확인.
- **runner 결선**: `EndpointExplorationRunner` 생성자는 13개 외부의존(SutProcess/Connection/CoverageClient 등)이라 실제 SQL 로그를 fake로 만들기 비현실적 → **runner 통합은 단위로 두지 않고**, §2.1-3 판정 로직(`needsPass2` 술어)과 헬퍼(`attachSeeds`)의 순수 부분만 단위로 검증하며, **end-to-end 결선은 §3의 실제 analytics 구동으로 확인**한다(이 한계를 명시).

---

## 4. 구현 단계 (TDD: red → green)

- **Step 1 — `ResolutionHint` + `ReadInputSynthesizer` hint 오버로드.**
  - red: `ReadInputSynthesizerHintTest`(§3.1). green: 오버로드 + resolve/map 분기 + pass1Target/heuristicParamColumns 노출. 기존 `ReadInputSynthesizerTest` 전건 유지.
- **Step 2 — `SqlSeedResolver.resolve(...)`.**
  - red: `SqlSeedResolverTest`(§3.1 7케이스). green: §2.2 규칙.
- **Step 3 — runner 보정형 2-pass 배선.**
  - `attachSeeds` 헬퍼 추출(현 215–279행, 동작 보존) → pass-1/pass-2 공용. needsPass2 술어·pass-2 시퀀스(§2.1-4)·INSERT 실패 폴백 배선.
  - 검증: 헬퍼/술어 순수 단위 + Step 4/E2E 실측.
- **Step 4 — 회귀 가드.** order-service e2e 45/45, 빌더 단위 전건 GREEN, petclinic/community no-op 확인.
- **Step 5 — (사용자 승인 조건부) `_json`/text 콘텐츠 시드.** `defaultFor`에서 컬럼명 `*_json`(또는 columnDefinition text) → `"[]"`(빈 유효 JSON)로 mindgraph byDiary 200 달성. **미승인 시 §3-2 종착 상태로 종료.** 승인 시 단위테스트(`*_json`→`"[]"`) + mindgraph 실구동 확인.

---

## 5. 회귀/리스크

- **SQL 파서 의존**: `SqlLogParser`는 Hibernate/MyBatis 로그 의존. SUT가 SQL 로그 미출력 → pass-1 SQL 빔 → hint=null → 보정 불가(현행과 동일, 회귀 아님). analytics/mindgraph는 로그 확인됨.
- **여러 SELECT / 우선순위**: (1) param 매칭(컬럼명→값) 바인딩 보유 SELECT 우선, (2) 없으면 WHERE 없는 집계는 FROM-only 첫 후보, (3) 동일 우선순위 복수면 등장 순 첫 번째. (§2.2와 일치 — 중복 제거)
- **잘못된 hint**: hint.table은 스키마 존재 테이블만 선정되므로 INSERT 대상 부재는 발생 안 함. 그래도 pass-2 INSERT 실패 시 **pass-1 결과 폴백**(§2.1-4 방어).
- **needsPass2=false 안전판**: 정상 해석 엔드포인트(community/petclinic/order)는 pass-2 미실행 → 불변 보장(핵심 회귀 방지).
- **explore 2회 비용**: 보정 대상 엔드포인트만. budget은 엔드포인트별이라 전체 영향 적음.
- **수용기준 측정성**: 200 코드가 아닌 seed 참조·`coveredAppBranches`·핀 커버리지로 falsifiable(§3 반영).

## 5.1 측정 결과 — 전 SUT 회귀 매트릭스 (2026-06-15 실측, 게이트 적용 후)

baseline = `main`(변경 전), gated = 워크트리(게이트). 동일 명령(`--budget-requests 60`), handler-class line 기준.

| SUT | infra | baseline line | gated line | re-explored | 판정 |
|---|---|---|---|---|---|
| petclinic | PG, auth | 244/660 (36%) | 244/660 (36%) | 0 | **회귀 0** (휴리스틱 해석) |
| community | MySQL+Kafka | 154/248 (62%) | 154/248 (62%) | 0 | **회귀 0** |
| notification | Redis+Kafka | 4/83 (4%) | 4/83 (4%) | 0 | 불변 (Redis, hint=null) |
| order-service | PG (e2e) | 45/45 PASS | 45/45 PASS | 0 | **회귀 0** (e2e) |
| **diary** | PG+Kafka | 81/208 (38%) | 131/208 (**62%**) | 4 | **+24 실제 수정** (`/diaries`≠`diary_entry`) |
| **analytics** | PG+Kafka | 25/119→(12%) | 25/119 (**21%**) | 2 | **+9 실제 수정** (`/mood`≠`mood_point`) |
| **mindgraph** | PG+Redis+Kafka | (5%) | 27/274 (**9%**) | 1 | **+4** (`/graphs`≠`graph_record`; byDiary seed, 응답 500=Step5 제외) |
| **auth-user** | MySQL+Redis | 28/251 (11%) | 35/251 (**13%**) | 1 | **+2 실제 수정** (`/users`≠`user_account`) |

- **회귀 0 보장**: 휴리스틱이 테이블을 해석하는 SUT(petclinic/community/notification/order)는 게이트로 재탐색
  자체를 안 해 baseline과 동일.
- **실제 개선**: resource명≠table명 4종(diary/analytics/mindgraph/auth-user)에서만 재탐색, 전부 커버리지 상승.
- analytics `getUserMood`: seed `mood_point`/`user_id`, 응답 `{points:[{score:1}], averageScore:1.0, count:1}`(빈→데이터). diary는 by-id GET/PUT/DELETE 4개가 `diary_entry`로 보정.
- (탐색 경로) broad(게이트 전) 버전은 community +17%/petclinic 동일이었으나, 그 +17%는 **동일 시드 재탐색의 측정 아티팩트**(community post SELECT 바인딩 없음 → hint 빈 paramColumn → 시드 불변)였고 petclinic은 `visits` 오선택이라 게이트로 제거. §2.1-3 참조.
- 참고: varchar-PK 시드 시 `identity resync ... COALESCE text/integer` WARN은 skip(non-fatal). 후속 정리 후보.

## 5.2 범위 밖(테스트 불가/무관)

- **counseling**: WebFlux, `@*Mapping` 컨트롤러 없음 → 빌더 엔드포인트 0. 빌드 jar 없음.
- **bff-gateway**: HTTP 집계 게이트웨이(자체 DB 없음), JDK21·빌드 jar 부재. SQL 시드 무관.

## 6. 관련 파일

- 수정: `ReadInputSynthesizer.java`(hint 오버로드, pass1Target/map 노출), `EndpointExplorationRunner.java`(`attachSeeds` 추출 + 2-pass 배선), (Step 5) `ReadInputSynthesizer.defaultFor`.
- 신규: `SqlSeedResolver.java`, `SqlSeedResolverTest.java`, `ReadInputSynthesizerHintTest.java`.
- 참조(무수정): `CapturedSql`/`SqlBinding`/`BindingOrigin`, `ParsedSql`, `Seeds`.
