# 에이전트 스킬 기반 삼중 합성 (Phase A) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-07-26-agent-skill-triple-synthesis-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)
> 범위: design spec의 **Phase A**. Phase B(LLM 갭필 자동화)·Phase C(attach egress 라우팅)는 Won't(🔵).

## 요구사항 목록

### C1 — provenance CLI

### REQ-001 — provenance 리포트 산출
- 유형: Functional / 우선순위: Must
- 설명: `provenance` CLI 서브커맨드가 엔드포인트 핸들러에서 재귀 슬라이스로 가드별 피연산자 출처 리포트(JSON)를 산출한다.
- 수용기준:
  - Given 보강된 fixture EP(transfers: DB 존재·balance 비교·외부 응답 비교·자유 텍스트 필드)와 SUT 소스, When `provenance` 실행, Then `provenance-report.json`의 guards[]에 3개 가드가 각각 INPUT/DB_READ/EXTERNAL_RESPONSE 출처·가드 위치·비교 op와 함께 기록되고 unguarded에 free-text 필드가 semanticHint와 함께 기록된다(golden 파일 일치).
- 검증 레벨: E2E black-box (CLI)

### REQ-002 — 재귀 깊이 cap·순환 종료
- 유형: Functional / 우선순위: Must
- 설명: 재귀 추적은 `--provenance-depth`(기본 3)와 방문 집합으로 제한되어 항상 종료한다.
- 수용기준:
  - Given 상호 재귀 호출을 포함한 소스, When depth 3으로 실행, Then 정상 종료하고 cap을 넘는 체인의 피연산자는 UNKNOWN으로 기록된다.
- 검증 레벨: integration

### REQ-003 — UNKNOWN 강등·표면화
- 유형: Functional / 우선순위: Must
- 설명: 정적 해석 실패(미해석 타입·리플렉션·프록시·인터페이스 다구현체)는 origin=UNKNOWN으로 태깅하고 `unresolved`에 표면화한다.
- 수용기준:
  - Given 구현체 2개인 인터페이스를 경유하는 가드, When `provenance` 실행, Then 그 피연산자는 UNKNOWN이고 unresolved에 사유(multi-impl)가 기록된다.
- 검증 레벨: integration

### REQ-004 — @Column/@Table 오버라이드 매핑
- 유형: Functional / 우선순위: Must
- 설명: DB_READ의 table/column 판정은 camelToSnake 휴리스틱에 더해 JPA `@Column(name=)`/`@Table(name=)` 오버라이드를 반영한다.
- 수용기준:
  - Given `@Column(name = "customer_email")` 오버라이드 엔티티 getter를 비교하는 가드, When `provenance` 실행, Then ValueRef.column은 `customer_email`이다.
- 검증 레벨: integration

### C2 — synthesize-triple CLI

### REQ-005 — 출처별 삼중 라우팅 산출
- 유형: Functional / 우선순위: Must
- 설명: `synthesize-triple`이 리포트를 받아 INPUT→body.json, DB_READ→seed.sql, EXTERNAL_RESPONSE→stubs.json으로 라우팅한 후보 디렉토리(+notes.md 근거 trace)를 산출한다.
- 수용기준:
  - Given REQ-001의 리포트, When `synthesize-triple` 실행, Then cand-01/에 body.json·seed.sql·stubs.json·notes.md가 생성되고 각 결정값에 가드 위치 trace가 notes.md에 기록된다.
- 검증 레벨: E2E black-box (CLI)

### REQ-006 — 관계 가드 공동 배치·경계 만족값
- 유형: Functional / 우선순위: Must
- 설명: `equals/비교(입력, DB값)` 관계 가드는 가드를 만족하는 같은/정합한 값을 입력과 시드 행에 동시 배치하고, 리터럴 경계 가드는 satisfy/경계 로직으로 만족값을 계산한다.
- 수용기준:
  - Given `repo.findById(req.fromAccountId)` 존재 가드와 `balance >= amount` 비교 가드, When 합성, Then seed.sql의 id=body의 fromAccountId이고 seed의 balance ≥ body의 amount가 성립한다.
- 검증 레벨: integration

### REQ-007 — 갭 마커 생성
- 유형: Functional / 우선순위: Must
- 설명: 결정적으로 도출 불가한 값만 갭 마커(`__AGENT_FILL__{type, semanticHint, guard}`)로 표기한다. 세 아티팩트(body/stubs jsonBody/seed 값 리터럴) 모두 마커를 지원한다.
- 수용기준:
  - Given 가드 없는 free-text 필드와 UNKNOWN 출처 컬럼, When 합성, Then 해당 위치만 마커이고 결정 가능한 값 위치에는 마커가 없다.
- 검증 레벨: integration

### REQ-008 — stubs.json = WireMock mapping 스키마
- 유형: Functional / 우선순위: Must
- 설명: stubs.json은 기존 external-stubs와 동일한 WireMock mapping JSON(`request.method/urlPath` + `response.status/jsonBody`)이며 기존 로더(`StubMapping.buildFrom`)로 로드 가능하다.
- 수용기준:
  - Given 합성된 stubs.json, When 기존 스텁 로더로 로드, Then 파싱 오류 없이 WireMock에 등록된다.
- 검증 레벨: integration

### T1 — 후보 검증 게이트

### REQ-009 — 마커 계약 강제(아티팩트별 diff)
- 유형: Functional / 우선순위: Must
- 설명: T1은 도구 생성 base와 후보를 diff해 마커 외 변경을 reject한다 — body/stubs는 JSON 키 단위, seed.sql은 파서 정규화(테이블, 컬럼→값) 비교로 **비-마커 값 변경도 reject**. notes.md는 검사하지 않는다.
- 수용기준:
  - Given 마커 아닌 body 필드를 바꾼 후보 / 비-마커 seed 컬럼 값을 바꾼 후보, When T1 로드, Then 둘 다 사유와 함께 reject된다. Given 마커만 채운 후보, Then 통과한다.
- 검증 레벨: E2E black-box

### REQ-010 — seed.sql 화이트리스트(JSqlParser)
- 유형: Functional / 우선순위: Must
- 설명: seed.sql은 JSqlParser로 검증한다 — INSERT만, provenance가 지목한 테이블 한정, DDL·UPDATE·DELETE·다중 스테이트먼트 reject. 정규식 검사는 사용하지 않는다.
- 수용기준:
  - Given 우회 시도 3종(세미콜론+주석 뒤 두 번째 문장, block-comment 내 DELETE, 문자열 리터럴 속 DELETE 키워드)과 비지목 테이블 INSERT, When 검증, Then 앞 2종과 비지목 테이블은 reject, 리터럴 속 키워드 INSERT는 통과한다.
- 검증 레벨: integration

### REQ-011 — 스키마 검증
- 유형: Functional / 우선순위: Must
- 설명: body는 BodyShape과, stub은 WireMock mapping 스키마·응답 형상과 대조해 미지 필드·형상 위반을 reject한다.
- 수용기준:
  - Given BodyShape에 없는 필드를 추가한 body 후보, When T1 로드, Then 사유와 함께 reject된다.
- 검증 레벨: integration

### REQ-012 — PII 휴리스틱 차단 semantics
- 유형: Functional / 우선순위: Must
- 설명: 실데이터 패턴 휴리스틱(best-effort)이 히트하면 해당 후보의 승격을 차단하고 사람 리뷰 대기로 표시한다(경고-통과 금지).
- 수용기준:
  - Given 실존 형식 전화번호/주민번호 패턴 값을 채운 후보, When 검증, Then promoted 불가 상태로 표시되고 사유가 리포트에 남는다.
- 검증 레벨: integration

### T2 — trial CLI

### REQ-013 — trial 실행·판정·승격 마킹
- 유형: Functional / 우선순위: Must
- 설명: `trial` CLI는 후보 적용(시드 resetSeeds→insert, 스텁 등록, body 주입) 후 캡처-off 경량 invoke로 판정(ResponseClassifier·엔벨로프 인지)하고, 성공 후보를 `promoted/`로 이동한다.
- 수용기준:
  - Given 유효한 후보와 부팅된 fixture SUT, When `trial` 실행, Then 2xx 판정 시 후보가 promoted/로 이동하고 실행 결과가 기록된다.
- 검증 레벨: E2E black-box (CLI)

### REQ-014 — FailureDigest 산출·가드 역매핑
- 유형: Functional / 우선순위: Must
- 설명: 실패 시 FailureDigest.json(status·outcome kind·응답 바디·SUT 로그 구간(byte-정합)·스택 발췌)을 산출하고, 스택 프레임↔가드 위치 대조 + 응답/로그 메시지↔가드 검증 메시지 매칭으로 mappedGuard를 판정한다. 둘 다 실패하면 mappedGuard:null. 규칙 수리 가능 실패(경계·enum·필수 필드)는 toolSuggestion을 포함한다.
- 수용기준:
  - Given balance 부족으로 422가 나는 후보, When trial, Then digest에 mappedGuard(해당 가드 위치)와 toolSuggestion(시드 balance 수정)이 담긴다. Given 매핑 불가한 실패, Then mappedGuard:null과 원시 로그 구간이 담긴다.
- 검증 레벨: integration

### REQ-015 — 캡처-off no-op scope
- 유형: Functional / 우선순위: Must
- 설명: trial invoke는 신설 no-op capture scope를 사용한다 — SQL 캡처 scope 미개설, 요청별 JaCoCo dump 스킵, 결과를 cumulativeCoverage/graph에 미병합.
- 수용기준:
  - Given trial invoke N회, When 이후 확정 run 산출물 검사, Then trial 구간의 SQL/커버리지/교환이 graph.json·리포트에 포함되지 않는다.
- 검증 레벨: integration

### REQ-016 — trial 예산·실패 회귀
- 유형: Functional / 우선순위: Must
- 설명: `--trial-budget`(기본 8) 소진 시 failed/에 최종 digest 보고서를 남기고 현행 base happy 경로로 회귀한다.
- 수용기준:
  - Given 전부 실패하는 후보들, When 예산 소진, Then failed/에 보고서가 생성되고 빌더 산출물은 후보 부재 시와 동일하다.
- 검증 레벨: E2E black-box

### REQ-017 — trial 시드 구간 직렬화
- 유형: Non-functional / 우선순위: Must
- 설명: trial의 시드 적용·invoke 구간은 병렬 탐색과 겹치지 않게 endpoint 단위 직렬로 실행된다.
- 수용기준:
  - Given parallelism>1 구성 회귀 스윕, When trial 포함 빌드 실행, Then 상태 간섭으로 인한 결과 차이가 없다(동일 산출).
- 검증 레벨: integration (회귀 스윕 구성)

### T3·파이프라인 통합

### REQ-018 — promoted 완주 경로(확정 run→TC green)
- 유형: Functional / 우선순위: Must
- 설명: 사전 커밋 promoted 후보를 `--triple-candidates`로 로드 → 검증 → trial 1회 재확인 → 캡처-on 확정 run → graph.json에 2xx ExploredPath 생성 → test-generator TC가 라이브 SUT에 green. 에이전트 불참으로 완주 가능해야 한다.
- 수용기준:
  - Given 사람 갭필로 부트스트랩한 promoted 후보(fixture EP), When 전체 빌드+생성+TC 실행, Then 대상 EP의 2xx ExploredPath와 생성 TC green이 확인된다.
- 검증 레벨: E2E black-box

### REQ-019 — 확정 run 실패 처리
- 유형: Functional / 우선순위: Must
- 설명: trial 성공 후 확정 run이 실패하면 그 후보를 폐기하고 리포트에 표면화하며 비결정 의심 사유를 첨부한다.
- 수용기준:
  - Given trial은 통과하나 확정 run에서 결과가 달라지는 후보(예: 시각 의존), When 실행, Then 후보가 채택되지 않고 리포트에 사유가 남는다.
- 검증 레벨: integration

### REQ-020 — stale-triple 표면화·회귀
- 유형: Functional / 우선순위: Must
- 설명: SUT 변경 등으로 promoted가 trial 재확인에 실패하면 조용히 버리지 않고 staleTriples로 표면화한 뒤 현행 경로로 회귀한다.
- 수용기준:
  - Given SUT와 불일치하는 promoted 후보, When 빌드, Then 리포트 staleTriples에 그 후보가 기록되고 나머지 산출물은 후보 부재 시와 동일하다.
- 검증 레벨: E2E black-box

### REQ-021 — 관측 필드 기록
- 유형: Functional / 우선순위: Must
- 설명: `EndpointExploration`에 trialCount/tripleAdopted/tripleRejected(사유별)/staleTriples를 신규 기록한다(JSON 하위호환 유지).
- 수용기준:
  - Given trial이 발화한 빌드, When exploration-report.json 검사, Then 위 필드로 발화·채택·거부·stale이 구분 확인된다. 기존 리포트 JSON은 계속 파싱된다.
- 검증 레벨: integration

### REQ-022 — 회귀 0 (ablation 비트-동일)
- 유형: Non-functional / 우선순위: Must
- 설명: `GRB_TRIAL=off`이거나 게이트 미발화(후보 부재·base happy 성공) 시 빌더 산출물은 현행과 비트-동일하다.
- 수용기준:
  - Given 동일 SUT·동일 설정, When off/미발화 vs 현행 main 빌드 비교, Then graph.json·리포트가 (신규 필드 기본값 제외) 동일하다.
- 검증 레벨: E2E black-box

### attach 안전 경계

### REQ-023 — attach seed 이중 opt-in
- 유형: Functional / 우선순위: Must
- 설명: attach 모드에서 seed.sql 적용은 `--attach-allow-seed`와 `--confirm-non-production`이 **둘 다** 있어야 활성화된다(기술적 가드). 하나라도 없으면 seed 미적용 + 사유 보고.
- 수용기준:
  - Given attach 구성에서 플래그 0개/1개/2개, When trial 시도, Then 0·1개는 seed 미적용·사유 기록, 2개일 때만 적용된다.
- 검증 레벨: integration

### REQ-024 — attach 역-DELETE 실패 시 승격 차단
- 유형: Functional / 우선순위: Must
- 설명: attach seed 활성 시 삽입 행을 추적해 종료 시 역-DELETE하며, 실패하면 해당 후보 승격을 차단하고 잔존 행(테이블·PK)을 리포트한다.
- 수용기준:
  - Given 역-DELETE가 실패하도록 만든 상황, When trial 종료, Then 후보가 promoted 되지 않고 잔존 행 리포트가 남는다.
- 검증 레벨: integration

### REQ-025 — attach EXTERNAL_RESPONSE 스텁 skip
- 유형: Functional / 우선순위: Must
- 설명: attach 모드에서는 스텁 등록을 시도하지 않고 EXTERNAL_RESPONSE 삼중을 inapplicable로 표시·skip하며 사유를 보고서에 남긴다(attach WireMock 라우팅은 Phase C).
- 수용기준:
  - Given EXTERNAL_RESPONSE 가드가 있는 promoted 후보와 attach 구성, When 빌드, Then 스텁 등록 시도 없이 skip 사유가 리포트에 기록된다.
- 검증 레벨: integration

### 스킬 3종·fixture

### REQ-026 — SKILL.md 3종 패키징
- 유형: Functional / 우선순위: Must
- 설명: `.claude/skills/{provenance-analysis, triple-synthesis, trial-loop}/SKILL.md`가 존재하고, 각각 frontmatter(name·description)·선행 산출물 가드 지시·"마커만 채워라" 지시를 포함한다.
- 수용기준:
  - Given repo 체크아웃, When 구조 검사 테스트 실행, Then 3개 SKILL.md의 존재·frontmatter 필드·핵심 지시 문구가 확인된다.
- 검증 레벨: unit (구조 검사)

### REQ-027 — 에이전트 완주 실증 (E2E-B1)
- 유형: Functional / 우선순위: Must
- 설명: 실제 에이전트가 스킬 3종으로 fixture 깊은-happy를 갭필→trial→승격까지 완주하고, 산출 diff로 마커만 변경했음을 확인해 기록을 남긴다.
- 수용기준:
  - Given fixture SUT와 스킬 3종, When 에이전트 세션이 C1→C2→C3 수행, Then promoted가 생성되고 diff 검사에서 마커 외 변경이 없으며 절차·결과가 문서로 기록된다.
- 검증 레벨: manual (수동 실증 1회 기록 — CI 게이트 제외)

### REQ-028 — fixture 착륙·outer red 고정
- 유형: Functional / 우선순위: Must
- 설명: wip 브랜치의 fixture EP 4종을 본 브랜치로 착륙시키고 transfers를 삼중 전부 필요 형태로 보강하며, **현행 합성으로 대상 EP가 2xx 미도달임을 동일-jar A/B로 고정**한다(outer red 전제).
- 수용기준:
  - Given 착륙·보강된 fixture, When 현행(트리플 미적용) 빌드, Then 대상 EP에 2xx ExploredPath가 없음이 E2E로 고정된다.
- 검증 레벨: E2E black-box

### 확증 실측 (수동/주기)

### REQ-029 — petclinic 커버리지 실측 (E2E-B2)
- 유형: Non-functional / 우선순위: Should
- 설명: petclinic 동일-jar A/B로 잔여 분기(145/253) 대비 상승폭을 실측하고 `coverage-progress.md`를 갱신한다.
- 수용기준:
  - Given 동일 petclinic jar, When 현행 vs Phase A 빌드 A/B, Then coveredAppBranches 변화가 기록·문서화된다.
- 검증 레벨: manual (주기 실증)

### REQ-030 — attach 경계 수동 확인 (E2E-B3)
- 유형: Functional / 우선순위: Should
- 설명: 실 SUT attach에서 seed 기본 off·이중 opt-in 경계 동작을 수동 확인한다(스텁은 REQ-025로 CI 검증되므로 범위 외).
- 수용기준:
  - Given 실 SUT attach 구성, When 플래그 조합별 시도, Then REQ-023 동작이 실 환경에서 재현됨을 기록한다.
- 검증 레벨: manual

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | provenance 리포트 산출 | ProvenanceCliE2E#REQ-001 (golden) | E2E | 🔴 planned |
| REQ-002 | 깊이 cap·순환 종료 | ProvenanceIndexerIT#REQ-002 | integration | 🔴 planned |
| REQ-003 | UNKNOWN 강등·표면화 | ProvenanceIndexerIT#REQ-003 | integration | 🔴 planned |
| REQ-004 | @Column/@Table 매핑 | ProvenanceIndexerIT#REQ-004 | integration | 🔴 planned |
| REQ-005 | 삼중 라우팅 산출 | TripleSynthesisE2E#REQ-005 | E2E | 🔴 planned |
| REQ-006 | 공동 배치·경계 만족값 | TripleSynthesizerIT#REQ-006 | integration | 🔴 planned |
| REQ-007 | 갭 마커 생성 | TripleSynthesizerIT#REQ-007 | integration | 🔴 planned |
| REQ-008 | WireMock mapping 스키마 | TripleSynthesizerIT#REQ-008 | integration | 🔴 planned |
| REQ-009 | 마커 계약 강제 | TripleGateE2E#REQ-009 | E2E | 🔴 planned |
| REQ-010 | seed.sql 화이트리스트 | SeedSqlWhitelistIT#REQ-010 | integration | 🔴 planned |
| REQ-011 | 스키마 검증 | TripleGateIT#REQ-011 | integration | 🔴 planned |
| REQ-012 | PII 차단 semantics | TripleGateIT#REQ-012 | integration | 🔴 planned |
| REQ-013 | trial 실행·승격 마킹 | TrialCliE2E#REQ-013 | E2E | 🔴 planned |
| REQ-014 | FailureDigest·역매핑 | TrialDigestIT#REQ-014 | integration | 🔴 planned |
| REQ-015 | 캡처-off no-op scope | TrialCaptureOffIT#REQ-015 | integration | 🔴 planned |
| REQ-016 | 예산·실패 회귀 | TrialCliE2E#REQ-016 | E2E | 🔴 planned |
| REQ-017 | trial 직렬화 | ParallelTrialRegressionIT#REQ-017 | integration | 🔴 planned |
| REQ-018 | promoted 완주 경로 | TriplePromotionE2E#REQ-018 | E2E | 🔴 planned |
| REQ-019 | 확정 run 실패 처리 | TriplePromotionIT#REQ-019 | integration | 🔴 planned |
| REQ-020 | stale-triple 표면화 | TriplePromotionE2E#REQ-020 | E2E | 🔴 planned |
| REQ-021 | 관측 필드 기록 | ExplorationReportIT#REQ-021 | integration | 🔴 planned |
| REQ-022 | 회귀 0 ablation | TrialAblationE2E#REQ-022 | E2E | 🔴 planned |
| REQ-023 | attach seed 이중 opt-in | AttachSeedGateIT#REQ-023 | integration | 🔴 planned |
| REQ-024 | attach 역-DELETE 실패 차단 | AttachSeedGateIT#REQ-024 | integration | 🔴 planned |
| REQ-025 | attach 스텁 skip | AttachStubSkipIT#REQ-025 | integration | 🔴 planned |
| REQ-026 | SKILL.md 3종 패키징 | SkillPackagingTest#REQ-026 | unit | 🔴 planned |
| REQ-027 | 에이전트 완주 실증 | manual: E2E-B1 절차·diff 기록 | manual | 🔴 planned |
| REQ-028 | fixture 착륙·outer red | FixtureBaselineE2E#REQ-028 | E2E | 🔴 planned |
| REQ-029 | petclinic 실측 | manual: E2E-B2 A/B 기록 | manual | 🔴 planned |
| REQ-030 | attach 경계 수동 확인 | manual: E2E-B3 기록 | manual | 🔴 planned |
| — | Phase B: LLM 갭필 자동화 | (별도 spec) | — | 🔵 out-of-scope |
| — | Phase C: attach egress 라우팅 | (백로그) | — | 🔵 out-of-scope |

Coverage: 0/30 green (0%) — target 100% (대상: Must 28 + 미연기 Should 2. Won't/Phase B·C: 🔵 분모 제외)

## design spec E2E ↔ REQ 매핑

| design spec E2E | REQ |
|---|---|
| E2E-A1 | REQ-001 (+002~004 내부 루프) |
| E2E-A2 | REQ-005~008 |
| E2E-A3 | REQ-018 |
| E2E-A4 | REQ-009~012 |
| E2E-A5 | REQ-016, REQ-022 |
| E2E-A6 | REQ-020 |
| E2E-B1 | REQ-027 |
| E2E-B2 | REQ-029 |
| E2E-B3 | REQ-030 |
| (선행 전제) | REQ-028 |
