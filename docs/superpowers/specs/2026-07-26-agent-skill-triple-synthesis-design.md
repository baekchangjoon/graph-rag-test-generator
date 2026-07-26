# 에이전트 스킬 기반 삼중 합성(입력·시드 SQL·스텁)으로 깊은 happy path 개방 — 설계 명세

- 작성일: 2026-07-26 (리뷰 반영 개정: 같은 날)
- 브랜치: `worktree-agent-skill-triple-synthesis`
- 대상 모듈: `graph-rag-builder`(CLI 서브커맨드·trial 모드) + repo `.claude/skills/`(에이전트 스킬 3종) + SUT fixture(`samples/order-service`)
- 상태: 브레인스토밍 5개 섹션 사용자 승인 → 설계 문서화 → **5-리뷰(Sonnet×3·Gemini·Cursor) 반영 개정본**
- 관련: `docs/24-exploration-backends-and-input-oracle.md`, `docs/25-input-discovery-theory.md`,
  `docs/22-static-discovery-limits.md`. Phase B 전제(**미머지 wip 브랜치**
  `origin/worktree-feat-llm-body-resynthesis`): 그 브랜치의
  `docs/superpowers/specs/2026-06-25-llm-body-resynthesis-design.md` — 본 브랜치에는 아직 없음.

---

## 1. 배경과 문제

graph-rag-builder는 정적 합성 + 탐색(실제 SUT HTTP 호출)으로 입력을 발견해 out-of-process
RestAssured 블랙박스 테스트를 생성한다. 그러나 **다중 가드(입력 검증 → DB 상태 비교 → 외부
응답 검증)를 모두 통과해야 하는 깊은 happy path(2xx)** 는 현행 파이프라인이 구조적으로 열지
못한다. 근거:

- petclinic 분기 커버리지 145/253(57.3%)에서 정체. 잔여는 비선형·interprocedural·집계·상태
  의존 가드(`docs/25` §9, `docs/coverage-progress.md`).
- 실제 외부 SUT attach 캠페인에서 동일 증상 관측(사용자 실측 진술): 얕은 4xx reject 경로
  다수 + 깊은 2xx 미도달 후 탐색 중단. (참고: tainted-spring 캠페인 문서
  `docs/2026-06-20-method1-tainted-spring-tool-gaps.md`는 인덱싱·재현 갭(G1~G5)이라는
  **관련되지만 별개의** 증상을 기록한다 — 본 설계의 다중-가드 원인 주장의 직접 근거는 아니다.)

구조적 원인(2026-07-26 세션 분석):

1. **happy 도달이 "1방 합성" 의존** — base happy가 실패하면 이후 변이 카탈로그는 대부분
   위반 변이(reject arm용)라 수리(repair) 방향성이 없다.
2. **novelty-only 시드 게이트 + saturation=2** — happy 근접 진전을 보상하지 않고 조기 종료.
3. **예산 산수** — 기본 60 req/EP는 깊이-2 이상 조합을 사실상 배제(카탈로그 크기 M>60 흔함).
4. **엔드포인트 고립 탐색** — 상태 의존 깊은 경로는 state-guard 정적 추출
   (`ConstraintExtractor`의 state-guard 경로, docs/24 Stage 4)의 인식 패턴에 한정.
5. **결정적 후보-집합의 원리적 한계** — 후보에 없는 값(외부 응답값·DB 행 런타임 값·의미값)은
   예산 무한이어도 못 찾음.

다른 세션에서 실증된 성공 방식: **컨트롤러·서비스 코드를 재귀 분석해 가드별 필요값·조건을
도출하고, 값의 출처에 따라 {입력 파라미터, DB 전처리 SQL, WireMock 스텁}을 함께 생성**.
본 설계는 이 방식을 파이프라인에 제품화한다.

## 2. 확정된 설계 결정 (브레인스토밍 합의)

| # | 결정 | 내용 |
|---|---|---|
| D1 | 정적 우선, LLM은 갭만 | 재귀 탐색·합성·시험은 **결정적 코드**가 수행. 코드가 도출 못 하는 값(의미값·UNKNOWN)만 에이전트가 창작 |
| D2 | 스킬 = SKILL.md + 결정적 코드 | 에이전트가 직접 재귀 탐색하지 않는다. 스킬의 코드(빌더 CLI 서브커맨드)가 탐색·합성·시험을 수행하고, SKILL.md는 "코드 실행 → 갭만 채움" 절차를 지시 |
| D3 | 탐색 확정 경유(무-fabrication) | 합성 삼중은 후보일 뿐. TC에 들어가는 것은 **확정 run의 관측물**(capturedSql·httpExchanges·seeds)뿐 |
| D4 | LLM 사후 게이트 + 갭 타게팅 | base happy 실패가 발화 조건. Phase A에서는 갭필이 오프라인(사람 또는 에이전트 수동)으로만 수행되고, **자동 LLM 호출은 Phase B에서 도입**(§3.2·§10) |
| D5 | trial 루프는 out-of-process | in-process(@SpringBootTest/@WebMvcTest) 시험은 기각(§9). 부팅된 SUT에 캡처-off 경량 invoke로 시험, 성공 시 캡처-on 확정 run 1회 |
| D6 | 성공 기준 | order-service 깊은-happy fixture(CI 강제) + petclinic 잔여 분기 실측(확증) |
| D7 | 캐시-커밋 모델 | 성공(promoted) 삼중을 repo에 커밋. CI·재실행은 커밋된 후보를 결정적으로 소비 — 에이전트는 새 갭 발생 시에만 재투입. promoted 커밋은 **표준 PR 리뷰 게이트 대상**이며 라이프사이클은 §5.4 |

## 3. 아키텍처

```
[에이전트 세계 — 스킬 3종, 갭 판단·창작만]      [빌더 세계 — 결정적, 탐색·합성·실행·검증·캡처]
  C1 provenance-analysis (SKILL.md)              provenance CLI 서브커맨드 (재귀 슬라이스+출처 태깅)
  C2 triple-synthesis   (SKILL.md)               synthesize-triple CLI (출처별 삼중 합성+갭 마커)
  C3 trial-loop         (SKILL.md)               trial CLI = T2 (경량 invoke+FailureDigest+수리 제안)
        │  갭 마커만 채움 / UNKNOWN 판정 / 비규칙 실패 창작
        ▼
  삼중 후보 {body.json, seed.sql, stubs.json} ──▶ T1 로더(--triple-candidates) → trial → 성공
        ◀── FailureDigest.json ──                → T3 확정 run(캡처 on) → graph.json
                                                  → test-generator(현행 무변경) → RestAssured 블랙박스 TC
```

### 3.1 설계 원칙

1. **무-fabrication**: D3. 에이전트가 무엇을 만들든 실행 관측이 최종 게이트.
2. **회귀 0**: trial 실패·게이트 미발화 시 현행 경로와 비트-동일. `GRB_TRIAL=off` ablation.
3. **결정성**: 결정적 코드 + 캐시-커밋 모델(D7)로 CI 결정적. 에이전트 개입은 갭 마커·UNKNOWN·
   비규칙 실패로 한정.
4. **UNKNOWN 강등 안전망**: 정적 해석 실패 지점은 UNKNOWN 태그 → 에이전트 갭 대상.
   정적 한계가 곧 에이전트의 입력.

### 3.2 Phase A 오케스트레이션 — 단일 시퀀스 (오프라인 스킬 구동)

Phase A의 trial 진입점은 **하나**다: 오프라인 스킬 시퀀스가 promoted를 만들고, 빌더 explore는
그것을 소비만 한다. 탐색 도중 에이전트를 호출하거나 기다리는 일은 없다.

```
[오프라인, 에이전트 세션] C1 → C2 → (갭필: 사람 또는 에이전트) → C3(trial CLI 반복) → promoted 커밋
[빌드 실행, 결정적]      explore 진입부에서 base happy invoke가 FAILURE이고 promoted가 존재하면
                          T1이 그 삼중을 적용 → trial 1회 재확인 → 확정 run → 현행 explore 계속
```

- 게이트 삽입 위치(현 트리 기준): `EndpointExplorationRunner`의 endpoint 탐색 진입부 —
  base happy 합성·invoke 직후, `ExplorationOrchestrator.explore` 호출 전.
- **Phase B**에서 같은 자리의 게이트가 wip 브랜치(**미머지** `worktree-feat-llm-body-resynthesis`)의
  LlmBody* 인프라(백엔드·캐시·예산·폴백)에 접속해 갭필을 자동화한다.

## 4. 컴포넌트

### 4.0 스킬 배치·형식·실행 순서

- **배치**: repo `.claude/skills/{provenance-analysis, triple-synthesis, trial-loop}/SKILL.md`
  (+ 얇은 래퍼 스크립트). 이 repo에는 스킬 선례가 없으므로 이 절이 형식의 기준이 된다.
- **frontmatter 최소 스펙**: `name`, `description`(트리거 매칭용, 영어) — Claude Code 프로젝트
  스킬 관례.
- **실행 순서**: 에이전트는 C1→C2→C3 순으로 호출한다. 각 SKILL.md는 선행 산출물(리포트/후보)이
  없으면 선행 스킬부터 실행하라는 가드 지시를 포함한다(별도 오케스트레이터 스킬 없음 — YAGNI).

### 4.1 갭 마커 계약 (핵심)

결정적 코드는 **세 산출물 모두**에서 채울 수 없는 자리만 명시적 마커로 표기하고, T1이 도구
생성 base와 diff해 마커 외 변경을 기계적으로 reject한다. 아티팩트별 diff 전략:

| 아티팩트 | 마커 표기 | T1 diff 방식 | 에이전트 변경 허용 범위 |
|---|---|---|---|
| `body.json` | 값 위치에 `__AGENT_FILL__{...}` | JSON 키 단위 구조 diff | 마커 값만 |
| `stubs.json` (WireMock mapping) | `response.jsonBody` 내 값 위치 | JSON 키 단위 구조 diff | 마커 값만 |
| `seed.sql` | INSERT 값 리터럴 위치에 `__AGENT_FILL__` 문자열 | **파서 레벨**: (테이블, 컬럼→값) 구조로 정규화 후 비교 — 텍스트 재포맷·주석에 비민감 | 마커 컬럼 값만. **비-마커 컬럼 값 변경도 reject**(가드 만족값 충실도 보장 — DB_READ 채널의 무-fabrication) |
| `notes.md`·리포트 주석 | — | 검사 안 함 | 자유(근거·사유 기록용) |

```json
{ "customerName": "__AGENT_FILL__{type:String, semanticHint:person-name, guard:none}",
  "accountId":    "ACC-7031",
  "amount":       42 }
```

SKILL.md 지시: "**마커만 채워라. 마커 아닌 값 수정 금지.**" — "정적 우선, LLM은 갭만"(D1)이
프롬프트 규율이 아닌 기계 계약으로 강제된다.

### 4.2 C1 `provenance-analysis` — 재귀 슬라이스 + 출처 태깅

- **결정적 코드**: graph-rag-builder CLI 서브커맨드 `provenance`. 기존 Spoon 자산
  (`SharedSpoonModel`·`MapperXmlIndexer`·`ResponseDtoIndexer`·`camelToSnake` 네이밍 휴리스틱)
  재사용. **신규 작업**: JPA `@Column(name=...)`/`@Table` 어노테이션 오버라이드 파싱 —
  현재 repo에는 이 자산이 없고 camelToSnake만 있는데, fixture `Booking.java`가 이미 명시적
  `@Column` 오버라이드를 쓰므로 이것 없이는 DB_READ의 table/column 판정이 조용히 틀린다.
  핸들러에서 호출 그래프를 재귀 추적(방문 집합·깊이 cap `--provenance-depth` 기본 3·순환
  가드, `ConstraintExtractor.reachableMethods` 1-hop의 일반화). 가드(throw/에러-return으로
  이어지는 조건식)에 도달하는 메서드 체인만 슬라이싱. DTO는 List/Map/중첩 포함 재귀 전개.
- **출력**: `provenance-report.json` — 가드별 `GuardFact { 위치, 비교 op, 피연산자[] }`,
  피연산자는 `ValueRef { jsonPath | table/column | callSite/stubField, origin, javaType, semanticHint }`.
- **origin 판정 규칙**:
  - `INPUT`: 핸들러 파라미터(@RequestBody/@PathVariable/@RequestParam 등) 유래.
  - `DB_READ`: repository/JPA/MyBatis mapper 반환값 유래(엔티티 getter 체인→컬럼 매핑).
  - `EXTERNAL_RESPONSE`: RestTemplate/WebClient/Feign 반환 DTO 유래(callSite 연계).
  - `DERIVED`: 위 출처값의 산술·문자열 파생(concolic 채널 위임 가능 표시).
  - `UNKNOWN`: 해석 실패 — noClasspath 미해석·리플렉션·프록시, 그리고 **인터페이스 구현체
    N개 미해소**(정적으로 주입 구현체 선택 불가 — `docs/22` §5의 기존 문서화된 한계) 포함.
- `semanticHint`(필드명·타입 기반: person-name/email/phone/free-text 등)는 태깅만 — 에이전트
  갭필 프롬프트 입력용.
- **SKILL.md**: CLI 실행법 + `unresolved`/UNKNOWN 항목만 에이전트가 소스를 열어 판정·보완하는
  절차(판정 근거를 리포트 주석으로 남김).

### 4.3 C2 `triple-synthesis` — 출처별 삼중 합성

- **결정적 코드**: CLI 서브커맨드 `synthesize-triple`. 리포트→삼중 라우팅:
  `INPUT`→`body.json` 필드 / `DB_READ`→`seed.sql` / `EXTERNAL_RESPONSE`→`stubs.json`.
  가드 만족값 계산은 기존 자산 재사용: `satisfy()`/경계 로직, concolic 채널(`ConcolicOracle`),
  **관계 가드(`equals(입력, DB값)`·비교)는 같은 값을 입력과 시드 행에 공동 배치**(docs/24
  Stage 4 "입력-시드 공동 합성"의 일반화).
- **`stubs.json` 포맷 = 기존 external-stubs WireMock mapping JSON 스키마 그대로**
  (`request.method/urlPath` + `response.status/jsonBody` — `HttpCaptureServer`가
  `StubMapping.buildFrom`으로 로드하는 기존 형식). C2가 인덱싱된 `ExternalCallSite`
  (httpMethod, pathLiteral, responseShape)로 mapping 뼈대를 생성하고, `ShapeJsonSynthesizer`
  형상 위에 가드 만족값을 덮어쓰며, 도출 불가 값은 `response.jsonBody` 안에 갭 마커.
  → 기존 로더·test-generator external-stubs 경로를 무변경 재사용.
- `seed.sql`도 갭 마커 포함 base로 생성(§4.1 — UNKNOWN 컬럼 값만 마커). 후보 수 cap +
  우선순위 정렬. 모든 결정값에 근거 trace(가드 위치)를 `notes.md`로 자동 생성.
- **SKILL.md**: 실행 후 **갭 마커만** 채우는 규칙 — semanticHint 준수, 제약(Bean Validation·
  가드) 위반 금지, 채운 값마다 사유 주석, 실존 인물·연락처 등 실데이터 금지(합성값만).

### 4.4 C3 `trial-loop` 스킬 + T2 `trial` CLI — 경량 시험·수리·승격

용어: **T2 = 결정적 CLI 러너**(후보 1개를 시험하고 digest를 내는 코드), **C3 = T2를 반복
구동하는 에이전트 스킬**(SKILL.md). 둘을 혼용하지 않는다.

- **T2 (결정적 코드)**: CLI 서브커맨드 `trial`. 후보 적용(시드 `resetSeeds`→insert, 스텁
  등록, body 주입) → **캡처-off 경량 invoke** → 판정(`ResponseClassifier`, 엔벨로프 인지).
  - **캡처-off의 실체(신규 모드)**: 현행 `doSend`는 항상 `sqlCapture.begin()`으로 scope를
    열므로 "캡처-off"는 기존 코드에 없는 모드다. **no-op capture scope 오버로드를 신설**한다
    — SQL 캡처 scope 미개설 + 요청별 JaCoCo dump 스킵 + 결과를 cumulativeCoverage/graph에
    미병합. trial 1회 비용 ≈ HTTP 왕복 + 시드 INSERT(수십 ms 수준)로, 확정 run(캡처 전체)
    대비 경량이라는 주장의 근거가 이 모드다.
  - 실패 시 `FailureDigest.json`: status·outcome kind·응답 바디·SUT 로그 구간(기존
    `logOffset`/`readLogRange` byte-정합 규율)·스택트레이스 발췌·**실패 가드 역매핑** —
    스택 프레임↔provenance 가드 위치 자동 대조. **전제와 한계**: 5xx·예외 로그는 스택이
    남지만, 4xx(`ResponseStatusException` 등)는 Spring 기본 로깅이 간결 WARN이라 스택이
    없는 경우가 일반적이다 → **응답·로그 메시지 텍스트↔가드 검증 메시지 매칭 휴리스틱을
    병용**하고, 둘 다 실패하면 `mappedGuard:null` 폴백(§6)이 일반 경로일 수 있음을 설계
    전제로 둔다. 규칙 수리 가능 실패(경계 ±1, enum 불일치, 필수 필드)는 `toolSuggestion`
    으로 수정 제안까지 산출.
- **C3 (SKILL.md)**: 루프 규율 — T2 실행→digest 판독→`toolSuggestion` 있으면 그대로 적용→
  재시도. **제안 불가(UNKNOWN 실패)일 때만** 에이전트가 digest(응답+로그)를 근거로 새 값
  창작(마커 계약 내에서)→재시도. 예산 `--trial-budget` **기본 8**(fuzzer 예산과 분리 —
  wip LlmBodyLoop의 N=3 선례에 정적 `toolSuggestion` 반복 여지를 더한 값; trial 1회가
  저비용이므로 여유 있게) 소진 시 실패 보고서 남기고 종료. 성공 시 후보를 `promoted/`로
  이동(승격 마킹).
- **직렬화 제약**: trial의 시드 적용은 SUT DB 전역 상태를 만지므로, **trial 구간은 병렬
  탐색과 겹치지 않게 직렬 실행**한다(빌더 parallelism>1이어도 trial/T1 시드 적용·invoke
  구간은 endpoint 단위 직렬 큐). 회귀 스윕에 parallelism>1 구성을 포함해 간섭 부재를 확인.

### 4.5 빌더 측 지원 (T1~T3)

- **T1 로더**: `--triple-candidates <dir>` — promoted 후보를 읽어 검증(§7) 후 시드 적용·스텁
  등록·base body 주입.
- **T2**: `trial` CLI 모드(§4.4).
- **T3 승격**: 확정 후보를 **캡처-on 1회 실행**해 관측물 채집 → 현행 explore·generator 경로
  무변경으로 ExploredPath/TC화.
- **관측 필드(신규)**: `ExplorationReport`의 `EndpointExploration` 레코드에
  `trialCount / tripleAdopted / tripleRejected(사유별) / staleTriples` 를 **신규 정의**한다
  (JSON 하위호환 생성자 전략은 기존 레코드 확장 관례를 따름). 선례로 인용하는
  `llmBodyCallCount`는 **미머지 wip 브랜치**(`worktree-feat-llm-body-resynthesis`)의 필드로,
  현 main에는 없다 — 본 필드들은 현 스키마 기준으로 직접 정의한다.
- 스킬 디렉토리는 SKILL.md + 얇은 래퍼 스크립트(CLI 호출·산출물 경로 관리)만 담는다.

## 5. 데이터 흐름과 아티팩트 포맷

### 5.1 예시 흐름 (깊은-happy fixture EP)

> **주의**: 아래는 **§11.1 보강 후의 목표 형태**다. 현재 `TransferController` fixture는
> 미머지 wip 브랜치(b60b9a3)에 있으며 400 응답·fraud 외부 호출 없음·note 필드 없음 상태다
> — fixture 착륙·보강이 Phase A 선행 작업이다(§11.1).

```java
POST /api/transfers  { fromAccountId, amount, note }
  ① account = repo.findById(req.fromAccountId)  → 없으면 404       (INPUT ↔ DB_READ)
  ② if (account.getBalance() < req.amount)       → 422              (DB_READ ↔ INPUT)
  ③ fraud = fraudClient.check(req); status != "CLEAR" → 409         (EXTERNAL_RESPONSE ↔ 리터럴)
  ④ note 자유 텍스트(검증 없음)                  → 201              (의미값 갭)
```

1. `provenance` → `provenance-report.json` (가드 3건 태깅 + `unguarded: note(free-text)`).
2. `synthesize-triple` → 후보 디렉토리:

```
.graphrag/triples/post-api-transfers/cand-01/
  body.json   { "fromAccountId":"ACC-1", "amount":100, "note":"__AGENT_FILL__{semanticHint:free-text}" }
  seed.sql    INSERT INTO accounts (id, balance) VALUES ('ACC-1', 100);   -- ①② 공동 배치
  stubs.json  [ { "request": { "method":"POST", "urlPath":"/fraud/check" },
                 "response": { "status":200, "jsonBody": { "status":"CLEAR" } } } ]   // WireMock mapping 스키마
  notes.md    각 값의 근거 trace(가드 위치) — 도구 자동 생성
```

3. 에이전트: 마커만 채움 + 사유 주석. `unresolved` 있으면 소스 판정 후 리포트 보완.
4. T2 trial 루프: 실패 시 digest(예: 422 + `mappedGuard: TransferService.java:44` +
   `toolSuggestion: {seed.sql: balance=101}`) → 제안 적용 or (제안 없으면) 에이전트 창작 → 재시도.
5. 성공 → `promoted/` 이동·커밋(표준 PR 리뷰 게이트 경유 — D7) → T3 확정 run(캡처 on) →
   graph.json ExploredPath.
6. test-generator(현행 무변경) → RestAssured 블랙박스 TC(시드 INSERT·스텁 로드는 기존
   RequiredSeed·external-stubs 메커니즘).

### 5.2 재실행·CI 경로 (에이전트 불참)

빌더가 `--triple-candidates`로 커밋된 promoted 후보 로드 → trial 1회 유효성 확인 → 확정 run →
현행 파이프라인. SUT 변경으로 trial 실패 시 **조용한 드롭 금지** — `staleTriples`로 표면화 +
현행 경로 회귀(재-에이전트 투입 신호). 이는 tainted-spring RFC P4의 "드롭 경로는 반드시
로그로 표면화" 결정과 같은 규범이다.

### 5.3 저장 위치와 디렉토리 레이아웃

```
<triple-store 루트>/                       # 기본: SUT 캠페인 디렉토리의 .graphrag/triples/
  <endpointId>/                            # 기존 endpoint id 규칙 그대로 (예: post-api-transfers)
    cand-01/ cand-02/ …                    # 미승격 후보 (순번 — 한 EP 복수 happy path 허용)
    promoted/cand-01/ …                    # 승격본 (커밋 대상; 순번 유지로 덮어쓰기 방지)
    failed/cand-01/ …                      # 예산 소진 실패본 + 최종 digest (진단용)
```

- 외부 SUT attach 캠페인은 `--triple-store <dir>` 플래그로 루트를 지정한다(기본은 위 경로).
- graph-rag repo의 fixture용 promoted는 `e2e/` 리소스로 커밋하고, e2e 스크립트가
  `--triple-candidates`에 그 상대 경로를 넘긴다(E2E-A3의 입력).

### 5.4 promoted 라이프사이클

- **정리 책임**: 엔드포인트가 제거·개명되면 해당 `<endpointId>/` 트리는 같은 변경에서 삭제한다
  (인덱싱 결과에 없는 endpointId의 promoted는 빌더가 `staleTriples`로 보고).
- **stale 처리**: trial 재확인 실패가 반복되는 promoted는 `failed/`로 강등하지 않고 그대로
  두되 리포트에 누적 표면화 — 제거는 사람이 PR로 결정(자동 삭제 금지).
- **리뷰 게이트**: promoted 커밋(에이전트 작성 SQL/스텁 포함)은 일반 코드와 동일하게 PR
  리뷰 게이트를 거친다(D7). 주기 감사(예: 릴리스 전)에서 `staleTriples` 누적분을 일괄 정리.

## 6. 에러 처리

| 실패 | 처리 | 원칙 |
|---|---|---|
| trial 예산 소진 | `failed/` + 최종 digest 보고서, **현행 base happy로 회귀** | 회귀 0 |
| digest→가드 역매핑 불가 | `mappedGuard:null` + 원시 로그 구간 → 에이전트 판단 | 도구는 모르면 모른다고 출력 |
| trial 성공→확정 run 실패 | 후보 폐기 + 리포트 표면화, 비결정 의심 사유(시각·랜덤) 첨부 | 확정 run이 최종 게이트 |
| 에이전트 부재 | 파이프라인 무변경(커밋된 promoted만 소비) | turnkey 경로 보존 |
| stale-triple | 표면화 + 현행 경로 회귀 (§5.2) | 드롭은 항상 가시화 |
| **attach 역-DELETE 실패** | **해당 후보 승격 차단** + 잔존 행(테이블·PK) 리포트, 이후 trial 중단 | 실 DB 잔존 상태로 승격 금지 |

## 7. 에이전트 산출물 검증 (결정적 게이트)

- **마커 계약 강제**: T1이 도구 생성 base와 diff — 아티팩트별 전략과 허용 범위는 §4.1 표.
  seed.sql은 파서 레벨 정규화 비교로 **비-마커 값 변경까지 reject**(DB_READ 채널의 값 충실도).
- **스키마 검증**: body↔BodyShape, stub↔WireMock mapping 스키마+응답 형상 대조, 미지 필드 reject.
- **seed.sql 화이트리스트 — 파서 명시**: **JSqlParser**(신규 의존성, `libs.versions.toml`
  추가)로 스테이트먼트 타입을 판정한다 — `INSERT`만 허용, 대상 테이블은 provenance가 지목한
  테이블 한정, DDL·UPDATE·DELETE·다중 스테이트먼트 reject. 정규식 검사는 문자열 리터럴 내
  키워드·주석 트릭·방언 인용부호에 취약하므로 채택하지 않는다. 지원 방언(Postgres/MySQL/
  MariaDB)별 파싱 차이는 단위 테스트로 고정하고, **우회 시도 테스트를 명시적으로 포함**한다:
  세미콜론+주석 뒤 두 번째 문장, block-comment 내 키워드, 문자열 리터럴 속 `DELETE` 등.
- **PII 금지**: SKILL.md 명시(합성값만) + 검증기 실데이터 패턴 휴리스틱. **semantics**:
  휴리스틱 히트 시 해당 후보의 **승격을 차단**하고 사람 리뷰 대기로 표시(경고만 하고 통과
  금지). 휴리스틱은 본질적으로 불완전하므로 D7의 PR 리뷰 게이트가 최종 방어선임을 명시.

## 8. 환경별 안전 경계

- **분석 환경(Testcontainers throwaway DB + 도구 소유 WireMock) — 기본**: 시드·스텁 자유.
  요청별 `resetSeeds`, 종료 시 기존 teardown·누수 검증 게이트 그대로.
- **attach 모드(실 외부 SUT)**:
  - `seed.sql` **기본 off**. opt-in은 이중 플래그 — `--attach-allow-seed` 와
    `--confirm-non-production`(환경이 비운영임을 명시 승인) **둘 다** 있어야 활성화되는
    **기술적 가드**로 한다(문서·경고만으로는 불충분). 활성 시 삽입 행 추적 → 종료 시
    역-DELETE(기존 `deleteSeeds` 패턴), 실패 시 §6의 승격 차단 + 잔존 행 리포트. 공유·운영
    DB 사용 금지는 SKILL.md와 CLI 경고에도 병기.
  - `stubs.json`: **attach 모드의 WireMock 라우팅은 현재 미구현이다** —
    `AttachedComposeEnvironment`에는 WireMock/외부 스텁 배선이 전무하고, WireMock은
    `AnalysisEnvironment`(Testcontainers)에만 존재한다. 따라서 **오늘 기준 attach의 모든
    EXTERNAL_RESPONSE 삼중은 항상 inapplicable/skip**이며(등록 시도 자체를 안 함, 해당
    가드는 UNKNOWN 실패로 보고서에 사유 기록), attach용 egress 라우팅 도입은 Phase A/B
    범위 밖의 별도 후속이다. E2E-B3의 검증 범위도 seed opt-in 경계 확인으로 한정된다(§11.3).

## 9. 기각 대안 (근거 보존)

| 대안 | 기각 사유 |
|---|---|
| in-process(@SpringBootTest/@WebMvcTest) trial | 비용 절감 실체 없음(컨텍스트 부팅 동일, WebMvcTest는 서비스 목화로 깊은 가드 무의미), 이기종 JDK/classpath 결합 재도입(`docs/25` §7의 out-of-process 채택 사유), attach 모드 불가, 성공해도 out-of-process 재확인 필요(이중 실행) + in/out 발산 위험 |
| ASM 바이트코드 interprocedural 솔버 일반화 | aliasing·경로 폭발·프레임워크 의미론을 떠안는 연구급 비용 — `docs/24` "interprocedural 전파" 보류 항목이 1차 근거(`decisions/explorer-engines.md`는 SUPERSEDED된 역사적 기록으로만 참조 — ASM+Z3 자체는 이미 `ConcolicOracle`로 도입돼 있음). 단 DB_READ 값이 선형식에 낄 때 기존 Z3 채널과의 접점은 후속으로 열어둠 |
| LLM-우선(슬라이스 전체를 LLM이 판단·합성) | 사용자 방향과 반대(D1: 정적 우선). 결정성·재현성·비용 열위 |
| 에이전트 직접 재귀 탐색(코드 없이 프롬프트만) | D2로 대체 — 탐색·합성은 결정적 코드가 수행, 에이전트는 갭만. 재현성·품질 편차·토큰 비용 우위 |

## 10. Phase 경계

- **Phase A (본 설계 범위)**: provenance·synthesize-triple·trial CLI + T1~T3 + 검증 게이트 +
  SKILL.md 3종 + fixture E2E. **에이전트 갭필은 Phase A 내내 수동 실증(E2E-B1)으로만 검증
  하며, 자동 LLM 호출은 Phase B에서 도입한다.** Phase A 개발 중 E2E-A3가 소비할 promoted
  후보는 **갭 마커를 사람이 채워 만든 후보로 부트스트랩해도 무방**하다(갭필 주체가 사람이냐
  에이전트냐는 A3의 검증 대상이 아님) — E2E-B1은 에이전트 주체의 완주를 별도로 실증한다.
- **Phase B (별도 spec)**: 갭필 자동화 — **미머지 wip 브랜치** `worktree-feat-llm-body-resynthesis`
  완주 후 그 인프라(LlmBodyBackends·캐시·예산·폴백)에 C2/C3 갭필을 접속.

## 11. 테스트 전략과 E2E 수용 기준

### 11.1 fixture (Phase A 선행 작업 포함)

**선행 작업**: 미머지 wip 브랜치의 order-service fixture EP 4종(fulfillment/transfers/
invoices/quotas, 커밋 b60b9a3)을 본 브랜치로 cherry-pick해 착륙시킨다 — 현재 이 브랜치의
`samples/order-service`에는 해당 EP가 없다. 착륙 후 최소 1개(transfers)를 **삼중 전부 필요
형태**(§5.1 목표 형태: DB 비교+외부 응답 검증+의미값 필드)로 보강. 전제 검증: **현행 합성으로
이 EP들이 2xx 미도달임을 동일-jar A/B로 먼저 고정**(outer red).

### 11.2 E2E — CI 강제 (에이전트 불참, 결정적)

| ID | 기준 |
|---|---|
| E2E-A1 | `provenance` CLI가 fixture EP에 기대 태깅(출처+가드 위치) 리포트 산출 — golden 비교 |
| E2E-A2 | `synthesize-triple`이 갭 마커 포함 삼중 산출, 결정값(공동 배치·경계) 정확, 검증기 통과 |
| E2E-A3 | 사전 커밋 promoted 후보(§10의 부트스트랩 — 사람 갭필 가능)로 T1→trial→확정 run→graph.json 2xx ExploredPath→생성 TC가 라이브 SUT green (에이전트 없이 완주) |
| E2E-A4 | 마커 외 변경 후보(비-마커 seed 값 변경 포함)·화이트리스트 위반 seed.sql이 사유와 함께 reject |
| E2E-A5 | trial 전부 실패 시 digest·보고서 산출 + 기존 산출물 비트-동일(회귀 0), `GRB_TRIAL=off`도 비트-동일 |
| E2E-A6 | stale-triple 시 조용한 드롭 없이 표면화 + 현행 경로 회귀 |

### 11.3 E2E — 수동/주기 실증 (CI 게이트 제외 명시)

| ID | 기준 |
|---|---|
| E2E-B1 | 스킬 3종을 실제 에이전트가 수행해 fixture 깊은-happy를 갭필→trial→승격 완주(마커만 채웠는지 diff 확인) |
| E2E-B2 | petclinic 동일-jar A/B — 잔여 분기(145/253) 대비 상승폭 실측, `coverage-progress.md` 갱신 |
| E2E-B3 | 실 SUT attach에서 seed 기본 off·이중 opt-in 플래그 경계 동작 확인(§8 — 스텁은 attach 미지원이므로 범위 외) |

### 11.4 내부 루프 (unit TDD)

- C1: origin 판정 규칙별(repository/외부클라이언트/파라미터 유래), 깊이 cap·순환 가드,
  미해석·인터페이스 다구현체→UNKNOWN 강등, `@Column`/`@Table` 오버라이드 매핑.
- C2: 공동 배치, 경계 만족값, 마커 생성 조건, 라우팅(컬럼↔jsonPath↔WireMock mapping),
  seed.sql 마커 base 생성.
- T2/T1: digest 추출(스택↔가드 역매핑 + 메시지-텍스트 매칭, byte-정합 로그 규율),
  검증기(아티팩트별 diff·JSqlParser 화이트리스트·우회 시도 케이스·PII 차단 semantics),
  로더, 승격, no-op capture scope.
- 회귀: 전 SUT 스윕(order-service e2e + petclinic + MSA 정적) 무회귀 + **parallelism>1
  구성 포함**(trial 직렬화 간섭 부재 확인) + 자원 정리 누수 게이트.

### 11.5 완료 정의

요구사항명세(후속 단계) 추적 매트릭스 in-scope REQ 100% green + §11.2 CI E2E 전부 green +
회귀 0 + E2E-B1 실증 1회 기록.

---

## 부록 — 5-리뷰 triage 요약 (2026-07-26)

리뷰 구성: Claude Sonnet(원 슬롯) + Gemini 3.5 Flash(재시도 성공) + Cursor(재시도 성공) +
Sonnet 폴백 2본(외부 CLI 1차 실패 시 규칙에 따른 대체 — 이후 원 리뷰어 성공으로 보너스 관점).

- **수용(반영)**: 스킬 배치·frontmatter·실행 순서 명시(§4.0) / Phase A 오케스트레이션 단일
  시퀀스 고정(§3.2) / seed.sql 검증을 JSqlParser + 우회 테스트로 구체화(§7) / seed.sql 값
  충실도 — 마커-diff를 세 아티팩트로 확장(§4.1) / attach WireMock 미구현 사실 명시(§8) /
  attach seed 이중 opt-in 기술 가드 + 역-DELETE 실패 행(§6·§8) / `@Column` 매핑을 신규
  작업으로 정정(§4.2) / 캡처-off 실체(no-op scope 신설) 명시(§4.4) / stubs.json을 WireMock
  mapping 스키마로 고정(§4.3) / wip 브랜치 한정어 일관 + 관측 필드를 현 스키마 기준 정의
  (§4.5) / §5.1 목표-형태 주의 + fixture 착륙 선행 작업(§11.1) / promoted 레이아웃·순번·
  `--triple-store`(§5.3) / 라이프사이클·PR 리뷰 게이트(§5.4) / C3/T2 용어 구분(§4.4) /
  UNKNOWN에 인터페이스 다구현체 명시(§4.2) / `--trial-budget` 기본 8(§4.4) / trial 직렬화
  제약(§4.4·§11.4) / 스택트레이스 전제 완화 + 메시지 매칭 병용(§4.4) / PII semantics(§7) /
  tainted-spring 인용 완화(§1) / `StateGuardOracle` 명칭 정정(§1) / superseded ADR 강등(§9) /
  E2E-A3↔B1 부트스트랩 명시(§10) / 참조 spec 문서의 wip 소속 명시(머리말).
- **부분 기각**: parallelism>1 전용 E2E 신설(Cursor I6) → 회귀 스윕에 구성 포함으로 갈음
  (trial 직렬화가 설계 결정이므로 전용 ID는 중복). / "regex 폴백으로 단순화"(Gemini I3의
  대안 제시) → 보안 게이트에 regex는 부적합, JSqlParser 채택으로 해소.
