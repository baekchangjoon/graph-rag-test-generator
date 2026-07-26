# 에이전트 스킬 기반 삼중 합성(입력·시드 SQL·스텁)으로 깊은 happy path 개방 — 설계 명세

- 작성일: 2026-07-26
- 브랜치: `worktree-agent-skill-triple-synthesis`
- 대상 모듈: `graph-rag-builder`(CLI 서브커맨드·trial 모드) + repo `skills/`(에이전트 스킬 3종) + SUT fixture(`samples/order-service`)
- 상태: 브레인스토밍 5개 섹션 사용자 승인 완료 → 설계 문서화 → 3-벤더 리뷰 대기
- 관련: `docs/24-exploration-backends-and-input-oracle.md`, `docs/25-input-discovery-theory.md`,
  `docs/22-static-discovery-limits.md`, wip 브랜치 `worktree-feat-llm-body-resynthesis`
  (`docs/superpowers/specs/2026-06-25-llm-body-resynthesis-design.md`)

---

## 1. 배경과 문제

graph-rag-builder는 정적 합성 + 탐색(실제 SUT HTTP 호출)으로 입력을 발견해 out-of-process
RestAssured 블랙박스 테스트를 생성한다. 그러나 **다중 가드(입력 검증 → DB 상태 비교 → 외부
응답 검증)를 모두 통과해야 하는 깊은 happy path(2xx)** 는 현행 파이프라인이 구조적으로 열지
못한다. 실측 근거:

- petclinic 분기 커버리지 145/253(57.3%)에서 정체. 잔여는 비선형·interprocedural·집계·상태
  의존 가드(`docs/25` §9, `docs/coverage-progress.md`).
- 실제 외부 SUT attach 캠페인과 tainted-spring MSA에서도 동일 증상: 얕은 4xx reject 경로
  다수 + 깊은 2xx 미도달 후 탐색 중단.

구조적 원인(2026-07-26 세션 분석):

1. **happy 도달이 "1방 합성" 의존** — base happy가 실패하면 이후 변이 카탈로그는 대부분
   위반 변이(reject arm용)라 수리(repair) 방향성이 없다.
2. **novelty-only 시드 게이트 + saturation=2** — happy 근접 진전을 보상하지 않고 조기 종료.
3. **예산 산수** — 기본 60 req/EP는 깊이-2 이상 조합을 사실상 배제(카탈로그 크기 M>60 흔함).
4. **엔드포인트 고립 탐색** — 상태 의존 깊은 경로는 `StateGuardOracle`의 정적 인식 패턴에 한정.
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
| D4 | LLM 사후 게이트 + 갭 타게팅 | base happy 실패 시에만 발화, 그것도 갭 마커 필드만 |
| D5 | trial 루프는 out-of-process | in-process(@SpringBootTest/@WebMvcTest) 시험은 기각(§9). 부팅된 SUT에 캡처-off 경량 invoke로 시험, 성공 시 캡처-on 확정 run 1회 |
| D6 | 성공 기준 | order-service 깊은-happy fixture(CI 강제) + petclinic 잔여 분기 실측(확증) |
| D7 | 캐시-커밋 모델 | 성공(promoted) 삼중을 repo에 커밋. CI·재실행은 커밋된 후보를 결정적으로 소비 — 에이전트는 새 갭 발생 시에만 재투입 |

## 3. 아키텍처

```
[에이전트 세계 — 스킬 3종, 갭 판단·창작만]      [빌더 세계 — 결정적, 탐색·합성·실행·검증·캡처]
  C1 provenance-analysis (SKILL.md)              provenance CLI 서브커맨드 (재귀 슬라이스+출처 태깅)
  C2 triple-synthesis   (SKILL.md)               synthesize-triple CLI (출처별 삼중 합성+갭 마커)
  C3 trial-loop         (SKILL.md)               trial CLI (경량 invoke+FailureDigest+수리 제안)
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

### 3.2 발화 지점

`EndpointExplorationRunner`의 base happy 합성 직후: SUCCESS면 현행 explore 계속(무변경),
FAILURE(비-2xx 또는 엔벨로프)면 trial 게이트 발화. wip 브랜치 LlmBodyLoop 게이트와 같은
자리이며, Phase B에서 그 인프라(게이트·캐시·예산·폴백)에 접속한다.

## 4. 컴포넌트

### 4.1 갭 마커 계약 (핵심)

결정적 코드는 산출물에서 **채울 수 없는 자리만** 명시적 마커로 표기한다:

```json
{ "customerName": "__AGENT_FILL__{type:String, semanticHint:person-name, guard:none}",
  "accountId":    "ACC-7031",
  "amount":       42 }
```

SKILL.md 지시: "**마커만 채워라. 마커 아닌 값 수정 금지.**" T1 로더가 도구 생성 base와 diff해
마커 외 변경을 기계적으로 reject한다(§7). "정적 우선, LLM은 갭만"(D1)이 프롬프트 규율이 아닌
기계 계약으로 강제된다.

### 4.2 C1 `provenance-analysis` — 재귀 슬라이스 + 출처 태깅

- **결정적 코드**: graph-rag-builder CLI 서브커맨드 `provenance`. Spoon 자산(`SharedSpoonModel`·
  `MapperXmlIndexer`·`ResponseDtoIndexer`·`camelToSnake`·`@Column` 매핑) 재사용.
  핸들러에서 호출 그래프를 재귀 추적(방문 집합·깊이 cap `--provenance-depth` 기본 3·순환 가드,
  `ConstraintExtractor.reachableMethods` 1-hop의 일반화). 가드(throw/에러-return으로 이어지는
  조건식)에 도달하는 메서드 체인만 슬라이싱. DTO는 List/Map/중첩 포함 재귀 전개.
- **출력**: `provenance-report.json` — 가드별 `GuardFact { 위치, 비교 op, 피연산자[] }`,
  피연산자는 `ValueRef { jsonPath | table/column | callSite/stubField, origin, javaType, semanticHint }`.
- **origin 판정 규칙**:
  - `INPUT`: 핸들러 파라미터(@RequestBody/@PathVariable/@RequestParam 등) 유래.
  - `DB_READ`: repository/JPA/MyBatis mapper 반환값 유래(엔티티 getter 체인→컬럼 매핑).
  - `EXTERNAL_RESPONSE`: RestTemplate/WebClient/Feign 반환 DTO 유래(callSite 연계).
  - `DERIVED`: 위 출처값의 산술·문자열 파생(concolic 채널 위임 가능 표시).
  - `UNKNOWN`: 해석 실패(noClasspath 미해석·리플렉션·프록시 등).
- `semanticHint`(필드명·타입 기반: person-name/email/phone/free-text 등)는 태깅만 — 에이전트
  갭필 프롬프트 입력용.
- **SKILL.md**: CLI 실행법 + `unresolved`/UNKNOWN 항목만 에이전트가 소스를 열어 판정·보완하는
  절차(판정 근거를 리포트 주석으로 남김).

### 4.3 C2 `triple-synthesis` — 출처별 삼중 합성

- **결정적 코드**: CLI 서브커맨드 `synthesize-triple`. 리포트→삼중 라우팅:
  `INPUT`→`body.json` 필드 / `DB_READ`→`seed.sql` / `EXTERNAL_RESPONSE`→`stubs.json`.
  가드 만족값 계산은 기존 자산 재사용: `satisfy()`/경계 로직, concolic 채널(`ConcolicOracle`),
  **관계 가드(`equals(입력, DB값)`·비교)는 같은 값을 입력과 시드 행에 공동 배치**(Stage 4
  "입력-시드 공동 합성"의 일반화). 스텁 값은 `ShapeJsonSynthesizer` 형상 위에 가드 만족값 덮어씀.
  도출 불가 필드는 갭 마커. 후보 수 cap + 우선순위 정렬. 모든 결정값에 근거 trace(가드 위치)를
  `notes.md`로 자동 생성.
- **SKILL.md**: 실행 후 **갭 마커만** 채우는 규칙 — semanticHint 준수, 제약(Bean Validation·
  가드) 위반 금지, 채운 값마다 사유 주석, 실존 인물·연락처 등 실데이터 금지(합성값만).

### 4.4 C3 `trial-loop` — 경량 시험·수리·승격

- **결정적 코드**: CLI 서브커맨드 `trial`(=T2). 후보 적용(시드 `resetSeeds`→insert, 스텁 등록,
  body 주입) → **캡처-off 경량 invoke**(negative-auth가 재사용하는 `doSend` 코어) → 판정
  (`ResponseClassifier`, 엔벨로프 인지). 실패 시 `FailureDigest.json` 산출:
  status·outcome kind·응답 바디·SUT 로그 구간(기존 `logOffset`/`readLogRange` byte-정합 규율)·
  스택트레이스 발췌·**실패 가드 역매핑**(스택 프레임↔provenance 가드 위치 자동 대조)·
  규칙 수리 제안(`toolSuggestion`: 경계 ±1, enum 불일치, 필수 필드 등 — 가능한 경우만).
- **SKILL.md**: 루프 규율 — trial 실행→digest 판독→`toolSuggestion` 있으면 그대로 적용→재시도.
  **제안 불가(UNKNOWN 실패)일 때만** 에이전트가 digest(응답+로그)를 근거로 새 값 창작→재시도.
  예산 `--trial-budget`(fuzzer 예산과 분리) 소진 시 실패 보고서 남기고 종료. 성공 시 후보를
  `promoted/`로 이동(승격 마킹).

### 4.5 빌더 측 지원 (T1~T3)

- **T1 로더**: `--triple-candidates <dir>` — promoted 후보를 읽어 검증(§7) 후 시드 적용·스텁
  등록·base body 주입.
- **T2**: `trial` CLI 모드(=C3의 코드).
- **T3 승격**: 확정 후보를 **캡처-on 1회 실행**해 관측물 채집 → 현행 explore·generator 경로
  무변경으로 ExploredPath/TC화.
- **관측 필드**: `ExplorationReport`에 `trialCount / tripleAdopted / tripleRejected(사유별) /
  staleTriples` (기존 `llmBodyCallCount` 선례 — 블랙박스 E2E 검증용).
- 스킬 디렉토리는 SKILL.md + 얇은 래퍼 스크립트(CLI 호출·산출물 경로 관리)만 담는다.

## 5. 데이터 흐름과 아티팩트 포맷

### 5.1 예시 흐름 (깊은-happy fixture EP)

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
  stubs.json  [ { "callSite":"fraud-check", "response": { "status":"CLEAR" } } ]
  notes.md    각 값의 근거 trace(가드 위치) — 도구 자동 생성
```

3. 에이전트: 마커만 채움 + 사유 주석. `unresolved` 있으면 소스 판정 후 리포트 보완.
4. `trial` 루프: 실패 시 digest(예: 422 + `mappedGuard: TransferService.java:44` +
   `toolSuggestion: {seed.sql: balance=101}`) → 제안 적용 or (제안 없으면) 에이전트 창작 → 재시도.
5. 성공 → `promoted/` 이동·커밋 → T3 확정 run(캡처 on) → graph.json ExploredPath.
6. test-generator(현행 무변경) → RestAssured 블랙박스 TC(시드 INSERT·스텁 로드는 기존
   RequiredSeed·external-stubs 메커니즘).

### 5.2 재실행·CI 경로 (에이전트 불참)

빌더가 `--triple-candidates`로 커밋된 promoted 후보 로드 → trial 1회 유효성 확인 → 확정 run →
현행 파이프라인. SUT 변경으로 trial 실패 시 **조용한 드롭 금지** — `staleTriples`로 표면화 +
현행 경로 회귀(재-에이전트 투입 신호).

### 5.3 저장 위치

후보·promoted는 SUT 캠페인 측 `.graphrag/triples/<endpointId>/`(커밋 대상). fixture는
graph-rag repo 내 e2e 리소스로 커밋.

## 6. 에러 처리

| 실패 | 처리 | 원칙 |
|---|---|---|
| trial 예산 소진 | `failed/` + 최종 digest 보고서, **현행 base happy로 회귀** | 회귀 0 |
| digest→가드 역매핑 불가 | `mappedGuard:null` + 원시 로그 구간 → 에이전트 판단 | 도구는 모르면 모른다고 출력 |
| trial 성공→확정 run 실패 | 후보 폐기 + 리포트 표면화, 비결정 의심 사유(시각·랜덤) 첨부 | 확정 run이 최종 게이트 |
| 에이전트 부재 | 파이프라인 무변경(커밋된 promoted만 소비) | turnkey 경로 보존 |
| stale-triple | 표면화 + 현행 경로 회귀 (§5.2) | 드롭은 항상 가시화(G4 교훈) |

## 7. 에이전트 산출물 검증 (결정적 게이트)

- **마커 계약 강제**: T1이 도구 생성 base와 diff — 마커 외 위치 변경 후보 reject.
- **스키마 검증**: body↔BodyShape, stub↔응답 형상 대조, 미지 필드 reject.
- **seed.sql 화이트리스트**: `INSERT`만, provenance가 지목한 테이블 한정, DDL·UPDATE·DELETE·
  다중 스테이트먼트 reject(파서 검증).
- **PII 금지**: SKILL.md 명시(합성값만) + 검증기 실데이터 패턴 휴리스틱(best-effort).

## 8. 환경별 안전 경계

- **분석 환경(Testcontainers throwaway DB + 도구 소유 WireMock) — 기본**: 시드·스텁 자유.
  요청별 `resetSeeds`, 종료 시 기존 teardown·누수 검증 게이트 그대로.
- **attach 모드(실 외부 SUT)**:
  - `seed.sql` **기본 off**. `--attach-allow-seed` 명시 opt-in 시에만: 삽입 행 추적 → 종료 시
    역-DELETE(기존 `deleteSeeds` 패턴), 실패 시 잔존 행 리포트. 공유·운영 DB 사용 금지를
    SKILL.md와 CLI 경고 양쪽에 명시.
  - `stubs.json`은 외부 의존이 도구 소유 WireMock으로 라우팅된 attach 구성에서만 유효.
    실제 외부로 나가는 구성이면 **inapplicable로 표시·skip**(등록 시도 안 함) — 해당 가드는
    UNKNOWN 실패로 남고 보고서에 사유 기록. 판정은 빌더가 아는 실행 환경 기술자 기준.

## 9. 기각 대안 (근거 보존)

| 대안 | 기각 사유 |
|---|---|
| in-process(@SpringBootTest/@WebMvcTest) trial | 비용 절감 실체 없음(컨텍스트 부팅 동일, WebMvcTest는 서비스 목화로 깊은 가드 무의미), 이기종 JDK/classpath 결합 재도입(`docs/25` §7의 out-of-process 채택 사유), attach 모드 불가, 성공해도 out-of-process 재확인 필요(이중 실행) + in/out 발산 위험 |
| ASM 바이트코드 interprocedural 솔버 일반화 | aliasing·경로 폭발·프레임워크 의미론을 떠안는 연구급 비용 — 본 repo가 반복 보류한 사유 유효(`docs/24`·`decisions/explorer-engines.md`). 단 DB_READ 값이 선형식에 낄 때 기존 Z3 채널과의 접점은 후속으로 열어둠 |
| LLM-우선(슬라이스 전체를 LLM이 판단·합성) | 사용자 방향과 반대(D1: 정적 우선). 결정성·재현성·비용 열위 |
| 에이전트 직접 재귀 탐색(코드 없이 프롬프트만) | D2로 대체 — 탐색·합성은 결정적 코드가 수행, 에이전트는 갭만. 재현성·품질 편차·토큰 비용 우위 |

## 10. Phase 경계

- **Phase A (본 설계 범위)**: provenance·synthesize-triple·trial CLI + T1~T3 + 검증 게이트 +
  SKILL.md 3종 + fixture E2E. **에이전트 갭필은 수동 실증(E2E-B1)까지** — LLM API 자동 호출 없음.
- **Phase B (별도 spec)**: 갭필 자동화 — wip 브랜치(`worktree-feat-llm-body-resynthesis`) 완주
  후 그 인프라(LlmBodyBackends·캐시·예산·폴백)에 C2/C3 갭필을 접속.

## 11. 테스트 전략과 E2E 수용 기준

### 11.1 fixture

wip 브랜치의 order-service fixture EP 4종(fulfillment/transfers/invoices/quotas, 커밋 b60b9a3)을
main으로 가져와 재사용, 최소 1개를 **삼중 전부 필요 형태**(DB 비교+외부 응답 검증+의미값 필드)로
보강. 전제 검증: **현행 합성으로 이 EP들이 2xx 미도달임을 동일-jar A/B로 먼저 고정**(outer red).

### 11.2 E2E — CI 강제 (에이전트 불참, 결정적)

| ID | 기준 |
|---|---|
| E2E-A1 | `provenance` CLI가 fixture EP에 기대 태깅(출처+가드 위치) 리포트 산출 — golden 비교 |
| E2E-A2 | `synthesize-triple`이 갭 마커 포함 삼중 산출, 결정값(공동 배치·경계) 정확, 검증기 통과 |
| E2E-A3 | 사전 커밋 promoted 후보로 T1→trial→확정 run→graph.json 2xx ExploredPath→생성 TC가 라이브 SUT green (에이전트 없이 완주) |
| E2E-A4 | 마커 외 변경 후보·화이트리스트 위반 seed.sql이 사유와 함께 reject |
| E2E-A5 | trial 전부 실패 시 digest·보고서 산출 + 기존 산출물 비트-동일(회귀 0), `GRB_TRIAL=off`도 비트-동일 |
| E2E-A6 | stale-triple 시 조용한 드롭 없이 표면화 + 현행 경로 회귀 |

### 11.3 E2E — 수동/주기 실증 (CI 게이트 제외 명시)

| ID | 기준 |
|---|---|
| E2E-B1 | 스킬 3종을 실제 에이전트가 수행해 fixture 깊은-happy를 갭필→trial→승격 완주(마커만 채웠는지 diff 확인) |
| E2E-B2 | petclinic 동일-jar A/B — 잔여 분기(145/253) 대비 상승폭 실측, `coverage-progress.md` 갱신 |
| E2E-B3 | 실 SUT attach에서 seed 기본 off·opt-in 경계 동작 확인 |

### 11.4 내부 루프 (unit TDD)

- C1: origin 판정 규칙별(repository/외부클라이언트/파라미터 유래), 깊이 cap·순환 가드,
  미해석→UNKNOWN 강등.
- C2: 공동 배치, 경계 만족값, 마커 생성 조건, 라우팅(컬럼↔jsonPath↔stubField).
- C3/T: digest 추출(스택↔가드 역매핑, byte-정합 로그 규율), 검증기(diff·SQL 파서·스키마),
  로더, 승격.
- 회귀: 전 SUT 스윕(order-service e2e + petclinic + MSA 정적) 무회귀 + 자원 정리 누수 게이트.

### 11.5 완료 정의

요구사항명세(후속 단계) 추적 매트릭스 in-scope REQ 100% green + §11.2 CI E2E 전부 green +
회귀 0 + E2E-B1 실증 1회 기록.
