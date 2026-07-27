# 에이전트 스킬 기반 삼중 합성 (Phase A) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-07-26-agent-skill-triple-synthesis-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항이 모두 ≥1개의 통과 수용 테스트를 가짐 (대상 매트릭스 전부 green)
> 범위: design spec의 **Phase A**. Phase B(LLM 갭필 자동화)·Phase C(attach egress 라우팅)는 Won't(🔵).
> 개정: 3-벤더 리뷰(Sonnet·Gemini·Cursor) 19개 finding 반영 (2026-07-26).

**용어**: C1=provenance-analysis 스킬, C2=triple-synthesis 스킬, **C3=trial-loop 스킬(T2 CLI를
반복 구동하는 에이전트 루프)**. T1=후보 로더·검증 게이트, T2=`trial` CLI 러너, T3=확정 run 승격.

## 요구사항 목록

### C1 — provenance CLI

### REQ-001 — provenance 리포트 산출
- 유형: Functional / 우선순위: Must
- 설명: `provenance` CLI 서브커맨드가 엔드포인트 핸들러에서 재귀 슬라이스로 가드별 피연산자 출처 리포트(JSON)를 산출한다. origin은 가드가 아니라 **피연산자(ValueRef) 레벨** 속성이다.
- 수용기준:
  - Given 보강된 fixture EP(transfers: DB 존재·balance 비교·외부 응답 비교·자유 텍스트 필드 — **선행: REQ-028**)와 SUT 소스, When `provenance` 실행, Then `provenance-report.json`의 guards[] 각 가드에 피연산자 배열이 있고 피연산자별 origin이 기록되며(존재·balance 가드는 INPUT과 DB_READ 피연산자를 함께, 외부 가드는 EXTERNAL_RESPONSE 피연산자와 리터럴을 함께), 세 가드가 세 origin 유형을 모두 커버하고, 가드 위치·비교 op·unguarded의 free-text 필드(semanticHint)가 golden 파일과 일치한다.
- 검증 레벨: E2E black-box (CLI)

### REQ-002 — 재귀 깊이 cap·순환 종료
- 유형: Functional / 우선순위: Must
- 설명: 재귀 추적은 `--provenance-depth`(기본 3)와 방문 집합으로 제한되어 항상 종료한다.
- 수용기준:
  - Given 상호 재귀 호출을 포함한 소스, When depth 3으로 실행, Then 정상 종료하고 cap을 넘는 체인의 피연산자는 UNKNOWN으로 기록된다.
- 검증 레벨: integration

### REQ-003 — UNKNOWN 강등·표면화
- 유형: Functional / 우선순위: Must
- 설명: 정적 해석 실패(미해석 타입·리플렉션·프록시·인터페이스 다구현체)는 origin=UNKNOWN으로 태깅하고 `unresolved` 배열에 표면화한다. unresolved 항목 스키마: `{ location(클래스:라인), reason(enum: no-classpath | reflection | proxy | multi-impl | depth-cap), targetType }`.
- 수용기준:
  - Given 구현체 2개인 인터페이스를 경유하는 가드, When `provenance` 실행, Then 그 피연산자는 UNKNOWN이고 unresolved에 `{location, reason: "multi-impl", targetType}` 항목이 기록된다.
- 검증 레벨: integration

### REQ-004 — @Column/@Table 오버라이드 매핑
- 유형: Functional / 우선순위: Must
- 설명: DB_READ의 table/column 판정은 camelToSnake 휴리스틱에 더해 JPA `@Column(name=)`/`@Table(name=)` 오버라이드를 반영한다.
- 수용기준:
  - Given `@Table(name=)` 오버라이드가 있는 엔티티의 `@Column(name = "customer_email")` getter를 비교하는 가드, When `provenance` 실행, Then ValueRef.column은 `customer_email`이고 ValueRef.table은 `@Table(name=)` 값과 일치한다.
- 검증 레벨: integration

### REQ-032 — DERIVED 태깅·concolic 위임
- 유형: Functional / 우선순위: Must
- 설명: 출처값의 산술·문자열 파생 피연산자는 origin=DERIVED로 태깅하고 concolic 채널 위임 가능 표시를 남긴다. 합성 시 concolic 해가 있으면 결정값으로, 없으면 UNKNOWN과 동일한 갭 마커 처리한다.
- 수용기준:
  - Given 입력 파생 산술 가드(예: `score*2 == 84`), When `provenance`+`synthesize-triple` 실행, Then 피연산자 origin=DERIVED이고 concolic 해(42)가 body 결정값으로 배치된다. Given concolic이 못 푸는 파생, Then 그 위치는 갭 마커다.
- 검증 레벨: E2E[^req032-level]

### REQ-034 — DTO 중첩 재귀 전개
- 유형: Functional / 우선순위: Must
- 설명: 요청 DTO의 List/Map/중첩 객체 필드까지 재귀 전개해 중첩 필드의 jsonPath(dot-path)와 origin을 태깅한다.
- 수용기준:
  - Given 중첩 DTO(List 원소 객체의 필드가 가드에 쓰이는 형태 — fixture EP 중 1개에 포함, 선행: REQ-028), When `provenance` 실행, Then 중첩 필드가 dot-path(**컬렉션 대표원소 규약, bracket 없이 `items.qty`** — 기존 `JsonPaths`/`InputMutator` element[0] 규약과 합치)로 식별되고 origin이 올바르게 태깅된다(golden 일치).
- 검증 레벨: integration

### C2 — synthesize-triple CLI

### REQ-005 — 출처별 삼중 라우팅 산출
- 유형: Functional / 우선순위: Must
- 설명: `synthesize-triple`이 리포트를 받아 INPUT→body.json, DB_READ→seed.sql, EXTERNAL_RESPONSE→stubs.json으로 라우팅한 후보 디렉토리(+notes.md 근거 trace)를 산출한다.
- 수용기준:
  - Given REQ-001의 리포트(선행: REQ-028), When `synthesize-triple` 실행, Then cand-01/에 body.json·seed.sql·stubs.json·notes.md가 생성되고 각 결정값에 가드 위치 trace가 notes.md에 기록된다.
- 검증 레벨: E2E black-box (CLI)

### REQ-006 — 관계 가드 공동 배치·경계 만족값
- 유형: Functional / 우선순위: Must
- 설명: `equals/비교(입력, DB값)` 관계 가드는 가드를 만족하는 같은/정합한 값을 입력과 시드 행에 동시 배치하고, 리터럴 경계 가드는 satisfy/경계 로직으로 만족값을 계산한다.
- 수용기준:
  - Given `repo.findById(req.fromAccountId)` 존재 가드와 `balance >= amount` 비교 가드, When 합성, Then seed.sql의 id=body의 fromAccountId이고 seed의 balance ≥ body의 amount가 성립한다.
- 검증 레벨: integration

### REQ-007 — 갭 마커 생성 (아티팩트별 문법)
- 유형: Functional / 우선순위: Must
- 설명: 결정적으로 도출 불가한 값만 갭 마커로 표기한다. 아티팩트별 문법 — body.json·stubs.json(jsonBody): JSON 문자열 값 `"__AGENT_FILL__{type, semanticHint, guard}"`; seed.sql: 컬럼 타입과 무관하게 **작은따옴표 문자열 리터럴** `'__AGENT_FILL__{...}'`(SQL 파싱 유지 — JSqlParser·T1이 마커 위치로 인식).
- 수용기준:
  - Given 가드 없는 free-text 필드와 UNKNOWN 출처의 numeric 컬럼, When 합성, Then body는 `"__AGENT_FILL__{...}"` 문자열, seed.sql은 `'__AGENT_FILL__{...}'` 리터럴로 표기되고 seed.sql이 JSqlParser로 파싱 가능하며, 결정 가능한 값 위치에는 마커가 없다.
- 검증 레벨: integration

### REQ-008 — stubs.json = WireMock mapping 스키마
- 유형: Functional / 우선순위: Must
- 설명: stubs.json은 기존 external-stubs와 동일한 WireMock mapping JSON(`request.method/urlPath` + `response.status/jsonBody`)이며 기존 로더(`StubMapping.buildFrom`)로 로드 가능하다.
- 수용기준:
  - Given 합성된 stubs.json, When 기존 스텁 로더로 로드, Then 파싱 오류 없이 WireMock에 등록된다.
- 검증 레벨: integration

### REQ-033 — 후보 수 cap·우선순위 정렬
- 유형: Functional / 우선순위: Must
- 설명: C2는 EP당 후보를 cap(기본 4) 이내로 생성하고 우선순위 정렬 순서로 배치한다(cand-01이 최우선).
- 수용기준:
  - Given cap을 초과할 만큼 후보 조합이 가능한 리포트, When 합성, Then 후보 디렉토리는 최대 4개이고 cand-01이 우선순위 최상위 조합이다(정렬 기준 결정적).
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
- 설명: seed.sql은 JSqlParser(신규 의존성)로 검증한다 — INSERT만, 허용 테이블 = **provenance가 지목한 DB_READ 테이블 + `TableSchema.foreignKeys()` 전이 참조 부모 테이블**(FK 제약 시드 성립용, 전이 폐포는 스키마 기준 결정적), DDL·UPDATE·DELETE·다중 스테이트먼트 reject. 정규식 검사는 사용하지 않는다(기존 `testlib`의 regex 기반 `SqlTableParser`는 이 보안 게이트에 재사용하지 않는다). 파서는 `DbConfig.Type`(Postgres/MySQL/MariaDB)에 맞게 구성하고 방언별 INSERT 파싱 차이를 단위 테스트로 고정한다.
- 수용기준:
  - Given 우회 시도 3종(세미콜론+주석 뒤 두 번째 문장, block-comment 내 DELETE, 문자열 리터럴 속 DELETE 키워드)과 비허용 테이블 INSERT, When 검증, Then 앞 2종과 비허용 테이블은 reject, 리터럴 속 키워드 INSERT는 통과한다. And FK 부모 테이블(DB_READ 집합 밖이지만 전이 참조) INSERT는 통과한다. And 3개 방언의 대표 INSERT(인용부호·escape 차이 포함)가 각각 올바르게 판정된다.
- 검증 레벨: integration

### REQ-011 — 스키마 검증
- 유형: Functional / 우선순위: Must
- 설명: body는 BodyShape과, stub은 WireMock mapping 스키마·응답 형상과 대조해 미지 필드·형상 위반을 reject한다.
- 수용기준:
  - Given BodyShape에 없는 필드를 추가한 body 후보, When T1 로드, Then 사유와 함께 reject된다.
  - Given mapping 스키마에 없는 키를 추가했거나 응답 형상에 없는 필드를 jsonBody에 넣은 stub 후보, When T1 로드, Then 사유와 함께 reject된다.
- 검증 레벨: integration

### REQ-012 — PII 휴리스틱 차단 semantics
- 유형: Functional / 우선순위: Must
- 설명: 에이전트가 채운 값(세 아티팩트의 마커 위치 전부)을 실데이터 패턴 휴리스틱으로 스캔한다. 최소 패턴: 한국 휴대전화(01X-XXXX-XXXX), 주민등록번호(6-7 자리 패턴), 실도메인 이메일(@gmail.com 등 — `example.com`류 예약 도메인은 허용). 히트 시 해당 후보를 `needsHumanReview=true`로 표시해 **승격을 차단**하고(경고-통과 금지) 사유를 `tripleRejected`에 기록한다.
- 수용기준:
  - Given 실존 형식 휴대전화 값을 마커에 채운 후보, When 검증, Then promoted 불가(needsHumanReview) 상태로 표시되고 사유가 리포트에 남는다. Given `probe@example.com`류 합성값, Then 통과한다.
- 검증 레벨: integration

### T2 — trial CLI

### REQ-013 — trial 실행·판정·승격 마킹
- 유형: Functional / 우선순위: Must
- 설명: `trial` CLI(T2)는 후보를 다음 시퀀스로 적용한다: ① 기존 happy 시드 정리(현행 `resetSeeds`의 reverse-DELETE 경로) → ② 후보 `seed.sql` INSERT(삽입 행 추적 — attach는 REQ-023/024 게이트 경유) → ③ 스텁 등록 → ④ body로 캡처-off 경량 invoke → 판정(`ResponseClassifier`, 엔벨로프 인지). 성공 후보를 `promoted/`로 이동한다.
- 수용기준:
  - Given 유효한 후보와 부팅된 fixture SUT(선행: REQ-028), When `trial` 실행, Then 위 시퀀스로 적용되어(이중 INSERT 없음) 2xx 판정 시 후보가 promoted/로 이동하고 실행 결과가 기록된다.
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

### REQ-016 — trial 예산 소진 시 오프라인 산출
- 유형: Functional / 우선순위: Must
- 설명: 오프라인 C3 루프에서 `--trial-budget`(기본 8) 소진 시 T2는 후보를 `failed/`로 이동하고 최종 digest 보고서를 남기며 비-promoted 종료 상태를 반환한다. (빌더 explore 산출물의 회귀 0은 REQ-022의 소관 — 본 REQ는 오프라인 trial CLI 산출로 한정.)
- 수용기준:
  - Given 전부 실패하는 후보들, When 오프라인 trial 루프가 예산을 소진, Then failed/에 후보와 최종 digest 보고서가 생성되고 promoted는 생성되지 않는다.
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
  - Given 사람 갭필로 부트스트랩한 promoted 후보(fixture EP — 선행: REQ-028), When 전체 빌드+생성+TC 실행, Then 대상 EP의 2xx ExploredPath와 생성 TC green이 확인된다.
- 검증 레벨: E2E black-box

### REQ-019 — 확정 run 실패 처리
- 유형: Functional / 우선순위: Must
- 설명: trial 성공 후 확정 run이 실패하면 그 후보를 폐기하고 리포트에 표면화하며 비결정 의심 사유를 첨부한다.
- 수용기준:
  - Given trial은 통과하나 확정 run에서 결과가 달라지는 후보(예: 시각 의존), When 실행, Then 후보가 채택되지 않고 리포트에 사유가 남는다.
- 검증 레벨: integration

### REQ-020 — stale-triple 표면화·회귀 (trial 재확인 실패)
- 유형: Functional / 우선순위: Must
- 설명: SUT 동작 변경으로 promoted가 trial 재확인에 실패하면 조용히 버리지 않고 staleTriples로 표면화한 뒤 현행 경로로 회귀한다. (endpoint 자체가 사라진 경우는 REQ-035.)
- 수용기준:
  - Given SUT와 불일치하는 promoted 후보, When 빌드, Then 리포트 staleTriples에 그 후보가 기록되고 나머지 산출물은 후보 부재 시와 동일하다.
- 검증 레벨: E2E black-box

### REQ-035 — endpoint 제거·개명 시 stale 보고
- 유형: Functional / 우선순위: Must
- 설명: 인덱싱 결과에 존재하지 않는 endpointId의 promoted 후보는 trial 시도 없이 staleTriples로 보고한다(제거·개명 감지 — REQ-020과 별개 트리거).
- 수용기준:
  - Given 인덱싱 결과에 없는 endpointId 디렉토리의 promoted 후보, When 빌드, Then trial 없이 staleTriples에 보고되고 나머지 산출물은 후보 부재 시와 동일하다.
- 검증 레벨: integration

### REQ-021 — 관측 필드 기록
- 유형: Functional / 우선순위: Must
- 설명: `EndpointExploration` 레코드에 신규 필드를 기록한다 — `trialCount`(int), `tripleAdopted`(boolean), `tripleRejected`(Map<String,Integer> — reject 사유별 건수), `staleTriples`(List<String> — 원소 포맷 `<endpointId>/promoted/cand-NN`, 스토어 루트 기준 상대 경로). 기존 JSON 하위호환은 기존 레코드 확장 관례(오버로드 생성자)로 유지한다.
- 수용기준:
  - Given trial이 발화한 빌드, When exploration-report.json 검사, Then 위 타입/구조로 발화·채택·거부·stale이 구분 확인된다. 기존 리포트 JSON은 계속 파싱된다.
- 검증 레벨: integration

### REQ-022 — 회귀 0 (ablation 정규화-동등)
- 유형: Non-functional / 우선순위: Must
- 설명: `GRB_TRIAL=off`이거나 게이트 미발화(후보 부재·base happy 성공) 시 빌더 산출물은 현행과 **정규화 비교로 동등**하다 — 신규 필드는 기본값(0/false/빈 컬렉션)으로만 존재하고, 비교는 제외 키 목록(신규 필드)+set-동등/정규화 diff(기존 Graph set-equiv 도구 패턴)로 판정한다.
- 수용기준:
  - Given 동일 SUT·동일 설정, When off/미발화 빌드 vs 현행 main 빌드 비교, Then 정규화 diff가 차이 0을 보고한다(신규 필드 기본값 제외 목록 명시).
- 검증 레벨: E2E black-box

### REQ-031 — 삼중 저장 레이아웃·순번 증번
- 유형: Functional / 우선순위: Must
- 설명: 삼중 저장은 `<triple-store 루트>/<endpointId>/{cand-NN | base/cand-NN | promoted/cand-NN | failed/cand-NN}` 레이아웃을 따른다. `candidates(endpointId)`는 top-level 대기 후보만 순번순으로 로드하고(base/promoted/failed 제외), `promote`/`fail`은 각각 promoted/failed로 이동한다. 순번 충돌 시 덮어쓰지 않고 다음 가용 순번으로 자동 증번한다. CLI에서 이 저장소를 노출하는 방법(플래그·기본 경로·e2e fixture 배치)은 REQ-036 소관이며, 이 REQ는 `TripleStore`의 저장 레이아웃·이동·증번 메커니즘 자체로 한정한다.
- 수용기준:
  - Given promoted/cand-01이 기존재하는 endpoint에 신규 후보(cand-01)를 승격, When 산출 구조 검사, Then 위 레이아웃이 준수되고 기존 promoted/cand-01은 보존되며 신규 승격은 cand-02로 증번된다.
- 검증 레벨: integration

### REQ-036 — 삼중 저장 CLI 계약 + e2e fixture 경로
- 유형: Functional / 우선순위: Must
- 설명: `TripleStore`(REQ-031)를 CLI로 노출한다 — 생성/소비 루트는 `--triple-store <dir>`(기본: SUT 캠페인 `.graphrag/triples/`), trial이 읽는 대기 후보 디렉토리는 `--triple-candidates <dir>`로 지정한다. e2e fixture용 `promoted/` 사본은 graph-rag repo `e2e/` 리소스로 커밋하고, e2e 스크립트가 그 경로를 `--triple-store`에 상대 경로로 전달한다. `TrialRunner`(REQ-013/014/016~020 등)가 BuilderCli 서브커맨드에 배선되는 시점에 함께 구현한다.
- 수용기준:
  - Given `--triple-store`/`--triple-candidates` 미지정, When CLI 실행, Then 기본 경로(`.graphrag/triples/`)가 적용된다.
  - Given e2e 스크립트가 커밋된 `e2e/` fixture 경로를 `--triple-store`로 전달, When synthesize→trial→승격 파이프라인 실행, Then `TripleStore`가 그 경로를 루트로 레이아웃(REQ-031)을 그대로 따른다.
- 검증 레벨: integration
- 담당: Task 12/18 (TrialRunner/BuilderCli 배선과 함께)

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
- 설명: `.claude/skills/{provenance-analysis, triple-synthesis, trial-loop}/SKILL.md`가 존재하고 필수 요소를 포함한다.
- 수용기준:
  - Given repo 체크아웃, When 구조 검사 테스트 실행, Then 각 SKILL.md가 (a) `name`/`description` frontmatter, (b) 선행 산출물 부재 시 선행 스킬부터 실행하라는 가드 지시, (c) "마커만 채워라(마커 외 수정 금지)" 지시 문구 — 세 요소를 모두 포함함이 확인된다.
- 검증 레벨: unit (구조 검사)

### REQ-027 — 에이전트 완주 실증 (E2E-B1)
- 유형: Functional / 우선순위: Should[^req027-reclass]
- 설명: 실제 에이전트가 스킬 3종(`provenance-analysis`→`triple-synthesis`→`trial-loop`(=T2 CLI 반복 구동))으로 fixture 깊은-happy를 갭필→trial→승격까지 완주하고, 산출 diff로 마커만 변경했음을 확인해 기록을 남긴다.
- 수용기준:
  - Given fixture SUT와 스킬 3종, When 에이전트 세션이 세 스킬을 순서대로 수행, Then promoted가 생성되고 diff 검사에서 마커 외 변경이 없으며 절차·결과가 문서로 기록된다.
- 검증 레벨: manual (수동 실증 1회 기록 — CI 게이트 제외)

### REQ-028 — fixture 착륙·outer red 고정
- 유형: Functional / 우선순위: Must
- 설명: wip 브랜치의 fixture EP 4종(**fulfillment/transfers/invoices/quotas** — java만 cherry-pick, 리소스는 String-자연키 스키마로 직접 작성)을 본 브랜치로 착륙시키고 transfers를 삼중 전부 필요 형태(+중첩 DTO 필드 1개 — REQ-034용)로 보강하며, **트리플 미적용 현행 빌드에서 대상 EP가 2xx 미도달임을 E2E로 고정**한다(outer red 전제 — 트리플 도입 후에는 `GRB_TRIAL=off`가 같은 조건의 A/B 대조군 역할). fixture 기반 REQ 전체의 선행 의존이다.
- 수용기준:
  - Given 착륙·보강된 fixture, When 현행(트리플 미적용) 빌드, Then 대상 EP에 2xx ExploredPath가 없음이 E2E로 고정된다.
- 검증 레벨: E2E black-box

### 확증 실측 (수동/주기)

### REQ-029 — petclinic 커버리지 실측 (E2E-B2)
- 유형: Non-functional / 우선순위: Should
- 설명: petclinic 동일-jar A/B로 잔여 분기(145/253) 대비 변화를 실측하고 `coverage-progress.md`를 갱신한다.
- 수용기준:
  - Given 동일 petclinic jar, When 현행 vs Phase A 빌드 A/B, Then coveredAppBranches가 **145 대비 순증**하거나, 순증하지 않으면 **원인 분석이 coverage-progress.md에 첨부**된 경우에만 green으로 판정한다(무조건 기록=green 금지).
- 검증 레벨: manual (주기 실증)

### REQ-030 — attach 경계 수동 확인 (E2E-B3)
- 유형: Functional / 우선순위: Should
- 설명: 실 SUT attach에서 seed 기본 off·이중 opt-in 경계 동작을 수동 확인한다(스텁은 REQ-025로 CI 검증되므로 범위 외).
- 수용기준:
  - Given 실 SUT attach 구성, When 플래그 조합별 시도, Then REQ-023 동작이 실 환경에서 재현됨을 기록한다.
- 검증 레벨: manual

### REQ-037 — negative-validation 파생 TC의 FK 시드 누락 (생성 TC 404)
- 유형: Functional / 우선순위: Should / **상태: 🟢 green(최종 fix wave에서 해소 — `[^req037-fixed]`)**
- 설명: `EndpointExplorationRunner`/`NegativeValidationSynthesizer`/test-generator 경로에서
  negative-validation(제약 위반 mutation) 파생 시나리오에 seed INSERT가 부착되지 않아, FK 의존
  엔드포인트(예: `xxxId` 필드가 부모 테이블 존재 가드에 걸리는 엔드포인트)의 생성 TC가 (기대하는
  422 등 대신) 404로 실패한다. Task 18이 `post-api-transfers`의 완주 E2E(REQ-018)를 처음 통과시키며
  노출시킨 **기존 서브시스템 결함**이다 — 원인 코드 경로 자체는 이 Phase의 삼중 합성 파이프라인이
  도입되기 전부터 존재했지만, 이 엔드포인트가 이 task 이전에는 한 번도 2xx에 도달한 적이 없어(REQ-028
  outer red) negative-validation 파생 시나리오가 실제 generate+run 사이클로 실행된 적이 한 번도
  없었다(상세 재현·근거는 `e2e/triples/post-api-transfers/promoted/cand-01/notes.md` §"별도로
  발견했으나..." 참조). 삼중 합성 파이프라인(T1/T2/T3) 자체의 결함이 아니므로 이 Phase 분모에서는
  제외하고(🔵) 별도 후속 task/버그 트래킹으로 넘긴다.
- 수용기준:
  - Given FK 의존 엔드포인트의 negative-validation 파생 TC, When 생성 TC 실행, Then 선언된
    `requiredSeeds`가 해당 TC에 부착되어 기대 상태코드(예: 422)가 재현된다.
- 검증 레벨: E2E black-box (생성 TC 실행)

## 추적 매트릭스

> 선행 의존: fixture를 Given으로 쓰는 테스트(REQ-001/005/006/013/018/020/034 등)는 **REQ-028이
> 선행**한다 — 구현 순서에서 REQ-028을 최우선으로 착수한다.

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | provenance 리포트 산출 (operand-level origin) | ProvenanceCliE2E#REQ-001 (golden) | E2E | 🟢 done[^unguarded-fix][^c4-readjudication] |
| REQ-002 | 깊이 cap·순환 종료 | ProvenanceIndexerIT#REQ-002 | integration | 🟢 done |
| REQ-003 | UNKNOWN 강등·unresolved 스키마 | ProvenanceIndexerIT#REQ-003 | integration | 🟢 done |
| REQ-004 | @Column/@Table 매핑 | ProvenanceIndexerIT#REQ-004 | integration | 🟢 done[^jpa-inherited-fix] |
| REQ-032 | DERIVED 태깅·concolic 위임 | **TripleSynthesisE2E#req032_cliPipelinePlacesConcolicSolutionInCandidateBody**(`provenance`→`synthesize-triple --sut-jar` CLI 완주), ProvenanceIndexerIT#REQ-032 (태깅 2건: 단일/다변수 derivedFrom), TripleSynthesizerIT#REQ-032 (배치 2건: concolic 해 42 결정값 / 못 푸는 파생 갭 마커) | E2E | 🟢 green[^derived-half] |
| REQ-034 | DTO 중첩 재귀 전개 | ProvenanceIndexerIT#REQ-034 | integration | 🟢 done |
| REQ-005 | 삼중 라우팅 산출 | TripleSynthesisE2E#REQ-005, LookupSucceededOutcomeTest(spurious seed 금지), GeneratorProvenSeedInheritanceTest(SUCCESS/200-엔벨로프 혼재 회귀) | E2E | 🟢 green |
| REQ-006 | 공동 배치·경계 만족값 | TripleSynthesizerIT#REQ-006 | integration | 🟢 green |
| REQ-007 | 갭 마커 생성(아티팩트별 문법) | TripleSynthesizerIT#REQ-007 | integration | 🟢 green[^jsql-defer] |
| REQ-008 | WireMock mapping 스키마 | TripleSynthesizerIT#REQ-008 | integration | 🟢 green |
| REQ-033 | 후보 cap·우선순위 정렬 | TripleSynthesizerIT#REQ-033 | integration | 🟢 green |
| REQ-009 | 마커 계약 강제 | TripleGateE2E#REQ-009, TripleGateIT#REQ-009(다중 행/행 순서/컬럼 순서 회귀 5건 포함), TriplePromotionE2E#REQ-018(완주 E2E에서 items 배열 마커-diff 재확인) | E2E | 🟢 green[^req009-e2e-confirmed][^c4-readjudication] |
| REQ-010 | seed.sql 화이트리스트(방언 포함) | SeedSqlWhitelistIT#REQ-010, TripleGateIT#req010_columnLessInsertRejected, TrialSeedNormalizedExecutionIT(재생성 SQL 실행), TrialSeedMySqlExecutableCommentIT(실 MySQL 실행형 주석 우회 차단) | integration | 🟢 green[^c4-readjudication] |
| REQ-011 | 스키마 검증(body+stub) | TripleGateIT#REQ-011 | integration | 🟢 green[^stub-shape-partial][^nested-list-schema-fix][^req018-done][^c4-readjudication] |
| REQ-012 | PII 차단 semantics | TripleGateIT#REQ-012 | integration | 🟢 green[^c4-readjudication] |
| REQ-013 | trial 실행·승격 마킹(시퀀스) | TrialCliE2E#req013_validCandidatePromotedWithoutDoubleInsert, TrialCliE2E#t1GateRejectsNonMarkerChangeInStandaloneCli, TrialCliE2E#documentedPipelineOrderWorksWithoutExplicitReportFlag(문서 순서 파이프라인), TrialSeedCleanupIT(정리 키 PK 해석·fail-closed 5건) | E2E | 🟢 green[^c4-readjudication] |
| REQ-014 | FailureDigest·역매핑 | TrialDigestIT(4케이스: 스택-매칭+제안·literal 폴백·null·성공) | integration | 🟢 green |
| REQ-015 | 캡처-off no-op scope | TrialCaptureOffIT#REQ-015 | integration | 🟢 green |
| REQ-016 | 예산 소진 오프라인 산출 | TrialCliE2E#req016_allFailingCandidatesExhaustBudgetAndReportFinalDigest | E2E | 🟢 green |
| REQ-017 | trial 직렬화 | ParallelTrialRegressionIT#req017_* (2케이스: 락 상호배제·2-endpoint 무중첩) | integration | 🟢 green |
| REQ-018 | promoted 완주 경로 | TriplePromotionE2E#req018_adoptedTripleProducesSuccessExploredPath | E2E | 🟢 green[^req018-done] |
| REQ-019 | 확정 run 실패 처리 | TriplePromotionIT#req019_confirmRunMismatchRejectsCandidateAndRestoresOriginal | integration | 🟢 green |
| REQ-020 | stale-triple(재확인 실패) | TriplePromotionE2E#req020_staleTripleOnTrialMismatchFallsBackToBaseline | E2E | 🟢 green |
| REQ-035 | endpoint 제거·개명 stale | TriplePromotionIT#req035_* (2케이스) | integration | 🟢 green |
| REQ-021 | 관측 필드 기록(타입 명시) | EndpointExplorationTest#REQ-021 | unit | 🟢 green |
| REQ-022 | 회귀 0 (정규화-동등) | TrialAblationE2E#REQ-022 | E2E | 🟢 green[^grb-trial-wired] |
| REQ-031 | 저장 레이아웃·순번 증번 | TripleStoreLayoutIT#REQ-031 | integration | 🟢 green |
| REQ-036 | 저장 CLI 계약 + e2e fixture 경로 | TripleStoreCliContractTest#REQ-036(기본 경로·플래그 우선순위 4케이스), run-e2e.sh(`--triple-store e2e/triples` — 실행 로그로 fixture 소비 확인) + TriplePromotionE2E#req018, TrialCliE2E#documentedPipelineOrderWorksWithoutExplicitReportFlag(단일 `--triple-store` 루트 synthesize→trial) | integration | 🟢 green[^req036-split][^req036-task18-partial][^req036-done] |
| REQ-023 | attach seed 이중 opt-in | AttachSeedGateIT#req023_* (4케이스: 0개/allow만/confirm만/2개) | integration | 🟢 green |
| REQ-024 | attach 역-DELETE 실패 차단 | AttachSeedGateIT#req024_reverseDeleteFailureBlocksPromotionAndReportsRemainingRow | integration | 🟢 green |
| REQ-025 | attach 스텁 skip | AttachStubSkipIT#req025_attachModeSkipsStubRegistrationEvenWithNonEmptyStub | integration | 🟢 green |
| REQ-026 | SKILL.md 3종 패키징(3요소) | SkillPackagingTest#REQ-026 | unit | 🟢 green |
| REQ-027 | 에이전트 완주 실증 | manual: E2E-B1 절차·diff 기록 | manual | 🟡 절차 준비[^task19-manual-procedures] |
| REQ-028 | fixture 착륙·outer red | FixtureBaselineE2E#REQ-028 | E2E | 🟢 green |
| REQ-029 | petclinic 실측(판정 분기) | manual: E2E-B2 A/B 기록 | manual | 🟡 절차 준비[^task19-manual-procedures] |
| REQ-030 | attach 경계 수동 확인 | manual: E2E-B3 기록 | manual | 🟡 절차 준비[^task19-manual-procedures] |
| REQ-037 | negative-validation 파생 TC의 FK 시드 누락 | LookupSucceededOutcomeTest#derivedFailurePathInheritsSeedFromProvenSiblingKeyValue + GeneratorProvenSeedInheritanceTest + `e2e/run-e2e.sh`(tests=85 failures=0) | E2E black-box | 🟢 green[^req037-fixed] |
| — | Phase B: LLM 갭필 자동화 | (별도 spec) | — | 🔵 out-of-scope |
| — | Phase C: attach egress 라우팅 | (백로그) | — | 🔵 out-of-scope |

[^req032-level]: 검증 레벨을 integration → **E2E**로 올린 근거와 그 한계를 함께 명시한다(문서 내 정의 블록·추적 매트릭스 두 표기 일치). 수용기준의 **양성 절반**(concolic 해 42가 body 결정값으로 배치)은 `TripleSynthesisE2E#req032_cliPipelinePlacesConcolicSolutionInCandidateBody`가 `provenance` → `synthesize-triple --sut-jar` **CLI 완주**로 검증한다 — 그래서 레벨은 E2E다. **음성 절반**(concolic이 못 푸는 파생 → 갭 마커) 중 *DERIVED 파생 루트에 마커가 찍히는 것* 자체는 `TripleSynthesizerIT#req032_unsolvableDerivedFallsBackToGapMarker`(integration, 합성기 직접 호출)가 검증한다. 이 배치를 E2E로 중복 확보하지 않은 근거는 두 가지다: ① CLI는 오라클을 만들어 `TripleSynthesizer.synthesize(...)`에 그대로 넘기는 **pass-through**이고 결정값/갭 마커 분기는 전적으로 합성기 안(`optionsFor`)에서 갈리므로, CLI 경로가 integration 경로와 다르게 동작할 여지가 구조적으로 없다. ② "오라클 해가 없을 때 CLI 산출물이 갭 마커가 된다"는 사실 자체는 이미 E2E로 덮여 있다 — `TripleSynthesisE2E#req005_cliProducesCandidateDirectoryWithTrace`가 오라클 플래그 없는 CLI 실행에서 `input-oracle: none`과 `body.json`의 `__AGENT_FILL__` 마커를 함께 단언한다. 즉 E2E로 덮이지 않은 잔여는 "해가 없는 **DERIVED 루트** 자리"라는 좁은 조합뿐이며, 그 조합은 합성기 단위에서 결정적으로 검증된다.

[^derived-half]: **해소됨(Phase A 후속 작업 1/3).** 이전 상태는 "태깅 절반만 완료"였다 — DERIVED 태깅(origin=DERIVED + javaType 유지)은 됐지만 concolic 해의 body 배치·"못 푸는 파생은 갭 마커" 처리가 미구현이었고, 그 근본 원인으로 "`ProvenanceIndexer.classifyOperand`가 DERIVED `ValueRef`의 `jsonPath`를 의도적으로 `null`로 남기므로(REQ-001 unguarded 오탐 방지) `TripleSynthesizer`가 오라클 해를 어느 body 경로에 배치할지 복원할 근거가 없다"고 기록돼 있었다. **재확인 결과 그 전제 중 절반은 이미 무효**였다: unguarded 오탐은 이후 `ProvenanceIndexer.analyze`의 `referencedInputPaths` accumulator(리프 인식 시점에 경로를 누적)로 별도 해결되어 `ValueRef.jsonPath`에 의존하지 않는다(`ProvenanceIndexerIT#req001_derivedGuardFieldNotUnguarded`). 남은 진짜 문제는 "이 DERIVED 피연산자가 **어느 입력 필드에서** 파생됐는가"를 C2에 전달할 채널이 없다는 것뿐이었다. **해소 방식:** `ValueRef`에 `derivedFrom: List<String>`(파생식이 읽는 INPUT 리프의 dot-path 목록)을 추가하고(9-arg 호환 생성자 유지, `@JsonInclude(NON_NULL)`이라 기존 golden 스키마 무변경 — REQ-001 golden 파일 수정 없음), `derivesFromTrackedOrigin`이 좌/우 피연산자를 단락 없이 모두 분류해 다변수 파생(`score * factor`)의 루트도 빠짐없이 담게 했다. `TripleSynthesizer`는 `unguarded` 필드와 DERIVED 파생 루트 필드를 동일한 **채움 슬롯**으로 통일해, 슬롯마다 `InputCandidates`(numeric/strings/reals) 해가 있으면 결정값 옵션을, 없으면 갭 마커를 부여하고 기존 REQ-033 cap/정렬 기계에 그대로 태운다. `jsonPath`를 파생 루트로 덮어쓰는 대안(B)은 채택하지 않았다 — 파생식 자신은 body의 한 필드가 아니고 다변수 파생은 단일 `jsonPath`로 표현할 수 없어 의미가 깨진다. **CLI 배선(수용기준 문면 재현):** 수용기준이 "When `provenance`+`synthesize-triple` 실행"으로 CLI 경로를 명시하므로, `BuilderCli.runSynthesizeTriple`이 실제로 `InputCandidates`를 만들어 넘기도록 배선했다 — 신규 선택 플래그 `--sut-jar`(ASM+Z3 `ConcolicOracle`) / `--sut-src`(+`--sut-resources`, `StaticLiteralOracle`)를 주면 build 경로와 동일한 조합(merge, `GRB_ORACLE=static`이면 concolic 제외)으로 오라클을 구성한다. 두 플래그가 모두 없으면 종전대로 빈 오라클로 동작하되 그 축소 사실을 로그와 각 후보 `notes.md`의 `input-oracle: none` 줄로 남겨 조용한 기능 축소를 막는다(`notes.md`는 `base/` 사본에 쓰이지 않으므로 REQ-009 마커-diff에 영향 없음). `CandidateLifter.lift`는 쓰지 않는다 — leaf-키→dot-path 승격은 mutableFields를 키로 쓰는 탐색 경로용이고 `TripleSynthesizer`는 반대로 슬롯 jsonPath의 마지막 세그먼트로 조회하므로 leaf-키 그대로가 맞다. **검증:** `TripleSynthesisE2E#req032_cliPipelinePlacesConcolicSolutionInCandidateBody`가 `provenance` CLI(derived 픽스처) → `synthesize-triple --sut-jar` CLI를 실제로 완주해 cand body에 `score=42`가 JSON 숫자 결정값으로 들어가고 `notes.md`에 `input-oracle: concolic-asm-z3(--sut-jar)` trace가 남는 것을 단언한다(jar는 테스트가 클래스 바이트코드를 `BOOT-INF/classes/` 레이아웃으로 zip해 만들며, 42는 주입값이 아니라 Z3가 푼 값). 단위/통합 레벨 보강: `ProvenanceIndexerIT#req032_derivedTagged`(derivedFrom=["score"]) + `#req032_multiRootDerivedCollectsEveryInputRoot`(["score","factor"]), `TripleSynthesizerIT#req032_derivedConcolicSolutionPlacedAsDecidedBodyValue` + `#req032_unsolvableDerivedFallsBackToGapMarker`(비선형 `score*factor` → 두 루트 모두 갭 마커). **잔여 한계(정확히 기술):** 오라클 플래그를 생략한 실행에서는 DERIVED 자리가 갭 마커로 남는다 — 이는 미구현이 아니라 입력 없는 실행의 정의된 동작이며 `notes.md`에 표면화된다.

[^jpa-inherited-fix]: Task 7(provenance CLI golden E2E)에서 order-service 실 fixture(`POST /api/transfers`)를 구동해 발견: 리포지토리 인터페이스가 `findById` 등을 재선언하지 않고 `JpaRepository<Entity, Id>`에서 그대로 상속받으면(실 SUT의 일반적 관례), noClasspath에서 `executable.getDeclaringType()`/반환 타입이 모두 해소되지 않아 DB_READ 태깅이 UNKNOWN으로 조용히 강등되는 회귀가 있었다 — REQ-001의 balance 가드 수용기준("INPUT과 DB_READ 피연산자를 함께")을 위반. `ProvenanceIndexer.repositoryEntityType`을 리시버 정적 타입 기반 판별 + `JpaRepository` 제네릭 인자 역산으로 수정하고, `ProvenanceIndexerIT#req004_inheritedRepositoryMethodNotRedeclared`(fixture: `jpa-inherited`)로 회귀 테스트를 추가했다(REQ-004 확장, 새 REQ-ID 아님 — 기존 요구사항의 커버리지 갭 보강).

[^jsql-defer]: REQ-007 수용기준의 "seed.sql이 JSqlParser로 파싱 가능하며"는 이 task(Task 9) 시점에는
**구조 검증으로 대체**했다 — graph-rag-builder 모듈에 JSqlParser 의존성이 아직 없고(REQ-010/T1이
"신규 의존성"으로 도입 예정), 이 task의 선언 파일 범위(TripleSynthesizer/BuilderCli)에 새 빌드
의존성 추가는 포함되지 않는다. 대신 `TripleSynthesizerIT#req007_gapMarkersOnlyAtUndecidablePositions`의
`isWellFormedSingleStatementInsert()`가 괄호 균형·따옴표 짝·단일 문장 종결을 구조적으로 확인해
"갭 마커를 포함해도 SQL 파싱이 깨지지 않는다"는 계약의 결정적 부분(마커가 작은따옴표 문자열
리터럴 형태를 유지하는지)을 검증한다. **의무**: Task 10(REQ-010, seed.sql 화이트리스트)에서
JSqlParser가 실제로 도입되면, 갭 마커가 포함된 seed.sql 리터럴(예:
`'__AGENT_FILL__{type:long, semanticHint:none, guard:none}'`)을 그 JSqlParser로 실제 파싱해
예외 없이 INSERT로 인식되는지 재검증하는 테스트를 추가해야 한다 — 구조 검증은 임시 대체물이며
실제 파서 검증을 갈음하지 않는다.

**해소(Task 10):** `com.github.jsqlparser:jsqlparser:5.3`을 `graph-rag-builder`에 도입했다
(`gradle/libs.versions.toml`/`graph-rag-builder/build.gradle.kts`). 위 의무대로
`SeedSqlWhitelistIT#req010_gapMarkerLiteralParsesAsInsertWithoutException`이 갭 마커 리터럴
(`'__AGENT_FILL__{type:long, semanticHint:none, guard:none}'`)을 포함한 seed.sql을
`CCJSqlParserUtil.parseStatements`로 실제 파싱해 예외 없이 단일 `Insert` 문장으로 인식됨을
재검증한다 — Task 9의 구조 검증(`isWellFormedSingleStatementInsert`)을 실제 파서 검증으로
갈음했다. 임시 대체물 의무는 이것으로 해소됐다.

[^stub-shape-partial]: Task 10 코드리뷰에서 Important로 지적: stub `jsonBody`의 마커 위치가
객체/배열 등 임의 구조로 대체될 수 있어 REQ-011 수용기준("응답 형상에 없는 필드를 jsonBody에 넣은
stub reject")이 부분적으로만 충족돼 있었다. **즉시 조치:** `TripleValidator.diffJson`이 마커
위치의 candidate 값이 스칼라(문자열/숫자/불리언)가 아니면(객체·배열) reject하도록 수정해
"완전히 새로운 구조를 몰래 들여오는" 경로는 막았다(`TripleGateIT#req011_stubJsonBodyMarkerReplacedWithObjectRejected`).
**남은 갭(연기):** `validate()` 시그니처에 응답 DTO 형상 파라미터가 없어, "jsonBody가 스칼라이되
그 필드 자체가 실제 응답 DTO에 없는 필드"인 경우(예: 존재하지 않는 필드명으로 마커를 새로 추가하는
경우)는 여전히 REQ-009 마커-diff(키 집합 동일성)로만 방어된다 — 이는 base와 다른 키 추가는 이미
reject되므로 실질적으로 커버되지만, 완전한 응답 DTO 형상 대조(별도 스키마 소스 도입)는 T1
범위 밖으로 후속 task로 이연한다.

[^unguarded-fix]: 코드리뷰에서 Critical로 지적: 최초 커밋은 `ProvenanceIndexer.analyze()`가 `unguarded`를 항상 빈 리스트로 반환하는 상태(후속 task 범위로 표시돼 있었음)에서 REQ-001을 🟢로 표기 — REQ-001 수용기준의 "unguarded의 free-text 필드(semanticHint)가 golden과 일치" 부분이 실제로는 미충족이었다. Task 9(갭 마커)가 이 출력을 소비하는 설계라 연기하지 않고 즉시 구현: `@RequestBody` 파라미터 타입을 재귀 전개(record canonical accessor/JavaBean getFoo·isFoo, List는 대표원소로 계속 전개, Map은 동적 키라 leaf 처리 — 기존 INPUT dot-path 관례 재사용)해 가드에 한 번도 참조되지 않은 필드를 `UnguardedField`로 수집하고, 필드명 기반 결정적 규칙(`ProvenanceIndexer#semanticHint` — email/phone·tel/name/note·memo·comment·description/그 외 String→free-text/비-String→none)으로 semanticHint를 부여했다. `ProvenanceIndexerIT#req001_unguardedFieldTagged`(basic fixture, userId 미참조 확인)로 회귀 테스트를 추가하고, golden에 실산출 기준 unguarded 2건(`note`, `items.sku` — 둘 다 String이고 다른 규칙에 매칭되지 않아 free-text)을 반영했다. 클래스 Javadoc의 "unguarded 필드 탐지는 후속 task 범위" 문구는 제거했다.

[^req036-split]: Task 11 코드리뷰에서 Important로 지적: 원래 REQ-031("삼중 저장 레이아웃·CLI
계약")이 저장 레이아웃(층/순번 증번, `TripleStore`)과 CLI 계약(`--triple-store`/
`--triple-candidates` 플래그, e2e `promoted/` fixture 경로)을 한 REQ-ID에 묶은 채 수용기준은
전자만 검증해, Task 11에서 REQ-031을 🟢로 표기하면 CLI 계약까지 완료된 것처럼 커버리지가
과대 표기되는 문제가 있었다. **조치:** REQ-031을 저장 레이아웃·순번 증번으로 범위를 좁혀
`TripleStoreLayoutIT`로 완전히 검증된 것만 🟢로 남기고, CLI 계약·e2e fixture 경로는 신규
**REQ-036**으로 분리해 `TrialRunner`(REQ-013/014/016~020 등)가 BuilderCli에 배선되는 시점
(Task 12/18)까지 🔴 planned로 이연했다. Task 11의 선언 파일 범위(TripleStore/
EndpointExplorationRunner)에는 애초에 CLI 서브커맨드 변경이 포함되지 않았다.

[^req036-task12-partial]: Task 12(`trial` CLI, REQ-013/014/016)가 REQ-036의 CLI 계약 중 다음만
배선했다 — ① `--triple-store <dir>`(기본 `.graphrag/triples`)와 ② 후보 전용 `--triple-candidates
<dir>`(미지정 시 `--triple-store`와 동일 경로) 두 플래그의 분리, 그리고 그 기본 경로 자체
(`BuilderCli.runTrial`). **미배선(그대로 🔴 planned 유지 — 완성 아님):** (a) SUT가 실제로 참조하는
외부 stub WireMock에 attach하는 방법 — 이 CLI는 자체 `HttpCaptureServer`를 기동하지 않으므로
`TrialRunner`의 stub 등록(③ 단계)은 이 CLI 경로에서는 항상 skip된다(`TrialRunner` 자체는 nullable
`HttpCaptureServer`를 받아 단위 레벨에서는 등록/제거를 지원 — `TrialDigestIT`가 그 계약을 커버하진
않고, CLI 미배선만 이 각주의 범위다), (b) `--graph`로 그래프 자산에서 Endpoint/happy 시드를 자동
로드하는 경로(REQ-018 T3 파이프라인 통합 소관) — 현재 `trial`은 `--http-method`/`--path`로
Endpoint를 직접 명시받고 happy 시드는 별도 JSON 파일(`--happy-seeds`)로 받는다, (c) e2e fixture
`promoted/` 커밋 경로(Task 18 소관). 이 세 항목이 남아 있어 REQ-036은 이번 task로도 완성되지
않았고 🔴 planned를 유지한다.

[^req018-done]: Task 14가 REQ-018의 **빌더 소비 부분**을 구현·검증했다 — `EndpointExplorationRunner`가
base happy invoke FAILURE인 endpoint에서 `TriplePromotionGate.attempt`(promoted 존재 확인→T1
재검증→trial 1회 재확인)를 거쳐 성공하면 그 삼중을 영속 적용하고 확정 run(캡처-on 재explore)까지
수행해, 확정 run이 SUCCESS면 채택(`tripleAdopted=true`)한 뒤 현행 explore 파이프라인을 그대로
이어간다(REQ-017 정적 락으로 직렬화). 이 경로 자체는 `TriplePromotionIT`/`ParallelTrialRegressionIT`가
직접 호출로 커버했으나, REQ-018 수용기준이 요구하는 **완주 E2E**("전체 빌드+생성+TC 실행 → 대상 EP의
2xx ExploredPath와 생성 TC green")는 미완이었다(🟡, 이전 각주 `req018-builder-part`).

**Task 18 완결:** 완주를 막던 선결 갭 — `TripleValidator.schemaViolationsForBody`가 중첩 리스트
dot-path(`items.sku`/`items.qty`)를 지원하지 않아(`BodyShapeExtractor.flatten()`이 `List<DTO>`
필드를 원소까지 전개하지 않고 top-level 리프 하나로 남김) 배열 바디 후보가 항상 T1에서 reject되던
문제 — 를 `isAllowedPath`(dot-path 접두사가 `allowed`의 top-level 필드와 일치하면 그 아래 중첩
서브트리를 허용, 완전히 새로운 top-level 필드는 여전히 reject)로 고쳤다(REQ-011 보강,
`[^nested-list-schema-fix]` 참조). 이 수정을 전제로 `e2e/triples/post-api-transfers/{base,promoted}/
cand-01`(사람 갭필 부트스트랩, spec §10 허용 — `fromAccountId`/`amount`/seed/stub은 결정적 값,
`note`/`items[0].{sku,qty}`만 마커→값 채움, 근거는 해당 `notes.md`)와 동반 `provenance-report.json`을
커밋하고, `TriplePromotionE2E#req018_adoptedTripleProducesSuccessExploredPath`(Testcontainers
Postgres + 실 order-service SUT, `-Dtriple.candidates=e2e/triples`)로 전체 빌드 실행 →
`post-api-transfers`의 2xx SUCCESS ExploredPath 확인 + `tripleAdopted=true` + `staleTriples`
비어있음 + `trialCount=1`을 단언했다(GREEN, 168초).

**완주 E2E 1차 시도에서 실측한 2번째 선결 갭(REQ-011 추가 보강):** 위 dot-path 수정만으로는 여전히
STALE이었다 — 실 order-service를 태워보니 `trial 재확인 실패(REQ-020): status=500`. 근본 원인:
`TripleSynthesizer.routeNegatedEqualityGuard`가 산출하는 WireMock stub은 `{"status","jsonBody"}`만
채우고 `Content-Type` 헤더를 넣지 않는데, WireMock은 `jsonBody`만 있는 mapping에 Content-Type을
자동으로 붙이지 않는다(최소 재현: `HttpCaptureServer`에 동일 stub을 등록해 raw HTTP 응답 헤더가
`matched-stub-id`/`transfer-encoding`뿐임을 확인). `FraudClient`의
`RestTemplate.postForObject(..., FraudResult.class)`가 Content-Type 부재로 메시지 컨버터를 못 찾아
예외를 던지고 `TransferController`가 이를 잡지 않아 SUT가 500을 반환했다 — REQ-008/011이 규정한
WireMock mapping 스키마 자체가 "jsonBody 응답은 타입 있는 HTTP 클라이언트에서 그대로 쓰인다"는
암묵적 전제를 만족하지 못하고 있었다. **수정:** `TripleValidator.STUB_RESPONSE_KEYS`에 `headers`를
추가해(내부 헤더명은 임의이므로 `checkKeys` 대상 아님, `jsonBody`와 동일 취급) 사람 갭필 후보가
`stub.response.headers.Content-Type=application/json`을 명시적으로 채울 수 있게 했다(회귀 테스트
`TripleGateIT#req011_stubResponseHeadersKeyAccepted`). 후보의 `stubs.json`에 이 헤더를 채운 뒤 위
E2E가 GREEN으로 전환됐다. **미해결로 남기는 부분:** `TripleSynthesizer`가 EXTERNAL_RESPONSE stub을
자동 생성할 때 Content-Type을 자동으로 채우지 않는 것 자체는 이 task의 선언 파일 범위(e2e fixture +
`TriplePromotionE2E`) 밖이라 고치지 않았다 — 향후 자동 생성 stub도 동일한 500 함정에 빠질 수 있으므로
별도 후속 task 필요(새 REQ-ID 없이 REQ-008 백로그로 기록, 상세는
`e2e/triples/post-api-transfers/promoted/cand-01/notes.md`).

**완주 E2E 2차 시도(shell `run-e2e.sh`)에서 실측한 3번째 갭:** 위 두 수정 후 in-process
`TriplePromotionE2E`는 GREEN이었지만, `e2e/run-e2e.sh`(`--triple-candidates e2e/triples` +
`request-transfers.json` 신규 배선)가 만든 실 docker-compose 스택에서 test-generator 생성
`TransfersPostTest.s201_1`은 **422**로 실패했다(candidate의 `amount=100`/seed
`balance_amount=100` 기준). 근본 원인: test-generator/탐색기가 "`xxxId` 필드 → FK 부모 행 자동
시드" 관례(`SampleInputSynthesizer.findFkTarget`/`defaultFor`)로 시나리오별 **독자적** seed 행을
만드는데, 이때 NOT NULL numeric 컬럼(`balance_amount`)에는 candidate의 `seed.sql` 값이 아니라
**제네릭 기본값(=1)**을 쓴다 — candidate의 `amount`가 실제로 만족해야 할 대상은 candidate 자신의
seed.sql이 아니라 이 제네릭 기본값이었다. **수정:** candidate의 `amount`/`balance_amount`를 `100`에서
`1`로 낮춰(둘 다 결정값, 마커 아님 — REQ-009 무관) `balance(1) < amount(1)`이 false가 되게 했다 —
재실행 후 `s201_1`(및 파생 변이 `s500e500_1/2`, `s404e404_1`)이 모두 GREEN으로 전환됐다(상세 트레이스는
`e2e/triples/post-api-transfers/promoted/cand-01/notes.md`).

**REQ-037로 추적하는 잔여 실패(REQ-018 범위 밖, 이 diff의 회귀 아님):** 같은 실행에서
negative-validation 파생 변이 2건 — `s422e422_1`(amount를 base(=1) 대비 +1 경계인 `2`로 mutate해
"잔액부족 422"를 노리는 변이), `s422e422_2`(`items` 필드를 통째로 drop해 "invalid items 422"를
노리는 변이) — 은 여전히 **404**로 실패한다(`tests=85, failures=2`). 두 시나리오 모두 생성 코드에
`scope.jdbc().update(...)` seed 삽입 자체가 없다(`fromAccountId`가 `scope.testId()` 기반 매 테스트
고유 id라 사전 시드 없이는 항상 계정 미존재 → 404) — candidate의 값 조정만으로는 고칠 수 없다(어떤
값을 쓰든 계정 자체가 없어 404가 먼저 발생). `EndpointExplorationRunner`의 negative-validation/
mutation 패스가 "제약 위반 변이"를 만들 때 FK 존재-가드에 필요한 시드 요구사항을 함께 추적하지
못하는(또는 test-generator의 per-test id 격리와 어긋나는) 구조적 갭이다. **정확한 표현(코드리뷰
정밀화):** 이 결함이 있는 코드 경로 자체는 이 task 이전부터(삼중 합성 파이프라인 도입 이전부터)
존재했지만, "이미 실패하던 테스트"였던 것은 아니다 — `TransfersPostTest`/`s422e422_1`/`s422e422_2`는
base에 아예 존재하지 않았다(REQ-028 outer red로 `post-api-transfers`가 한 번도 2xx에 도달하지 못해
negative-validation 파생 시나리오가 실제 generate+run 사이클로 실행된 적이 없었다). 즉 **이 task가
처음 노출시킨, 원인 코드는 기존에 있던 서브시스템 결함**이다. `EndpointExplorationRunner`/
`test-generator`는 이 task의 선언 파일 범위(e2e fixture + `run-e2e.sh` 배선 + `TriplePromotionE2E`)
밖이라 여기서 고치지 않았다 — **REQ-037**(신규, 🔵 out-of-scope, Phase A 분모 제외)로 추적한다.

**REQ-018 판정:** 수용기준이 요구하는 것은 "대상 EP의 2xx ExploredPath와 (그) 생성 TC green"이며,
이는 in-process(`TriplePromotionE2E`, 2/2 GREEN)와 shell 기반 실 e2e(`TransfersPostTest.s201_1`
GREEN, 실 docker-compose 스택) 양쪽에서 독립적으로 확인됐다 — REQ-018 자체는 🟢(코디네이터 리뷰
Approved: "REQ-018 완주가 산출물로 독립 검증됨, 실패 2건은 이 diff의 회귀 아님"). 같은 실행에
포함된 negative-validation 파생 변이 2건은 REQ-037로 별도 추적되며(🔵, Phase A 분모 제외),
`e2e/run-e2e.sh` 전체 스위트는 현재 `tests=85 failures=2`이지만 그 2건은 REQ-037의 기지(旣知) 결함
범위이므로 REQ-018/삼중 합성 파이프라인 자체의 회귀로 보지 않는다.

[^req036-task18-partial]: `[^req036-task12-partial]`이 남긴 3항목 중 **(c) e2e fixture `promoted/`
커밋 경로**는 이 task가 완료했다 — `e2e/triples/post-api-transfers/{base,promoted}/cand-01` +
`provenance-report.json`을 커밋하고 `e2e/run-e2e.sh`의 빌더 호출에 `--triple-candidates e2e/triples`를
배선해, e2e 스크립트가 커밋된 fixture 경로를 그대로 소비함을 확인했다(REQ-036 수용기준 2번째
Given/When/Then 충족). **여전히 미배선(🟡 유지 사유):** (a) attach 모드에서 실제 SUT가 참조하는
외부 stub WireMock에 attach하는 경로, (b) `trial` 서브커맨드가 `--graph`로 그래프 자산에서
Endpoint/happy 시드를 자동 로드하는 경로 — 둘 다 이 task의 선언 파일 범위(triple fixture 배치 +
`run-e2e.sh` 배선 + `TriplePromotionE2E`) 밖이며 별도 후속 task로 남긴다.

[^req027-reclass]: **Must → Should 재분류(Phase A 후속 작업 2/3, 근거 기록).** 재분류 이전에 이 문서는 REQ-027을 `우선순위: Must`로 정의해 두고도 완료 정의 콜아웃에서는 "manual Should"로 서술해 Must 분모에서 빼고 있었다 — 두 표기가 모순이었고, 그 상태로 "Must 전부 🟢"을 주장하면 실제(33/34)를 34/34로 과대 표기하게 된다. 표기를 실제에 맞추는 방향(콜아웃을 고쳐 33/34로 적는 것)과 우선순위를 실제 성격에 맞추는 방향 중 **후자**를 택했다. 근거: REQ-027은 **에이전트 주체의 1회 수동 실증(E2E-B1)**이라 자동 실행·재현이 불가능하고, 이 문서가 이미 검증 레벨을 `manual`로 규정해 CI 게이트 대상에서 제외하고 있다(같은 성격의 REQ-029/030이 애초에 Should인 것과 일관). 스킬 3종의 **자동 검증 가능한 부분**(패키징 3요소 구조)은 REQ-026(Must, 🟢, `SkillPackagingTest`)이 이미 커버하므로, REQ-027을 Should로 두어도 Must 집합이 보장하는 기능 범위에는 구멍이 생기지 않는다. **다만 이 재분류는 요구사항을 삭제하거나 완화하는 것이 아니다** — REQ-027은 🟡(절차 준비)로 추적 매트릭스에 그대로 남고, 전체 37개 100% green 목표의 분모에도 그대로 포함된다. 즉 "Must 완료"와 "전체 완료"를 구분해 읽어야 하며, Phase A를 전부 완료로 선언하려면 REQ-027/029/030 3건의 manual 실증이 여전히 필요하다.

[^req036-done]: **해소됨(Phase A 후속 작업 2/3).** `[^req036-task12-partial]`/`[^req036-task18-partial]`이 남긴 잔여를 수용기준 문면 기준으로 재판정하고 미충족분만 채웠다.

**수용기준 ①(기본 경로) — 미충족이었고, 구현했다.** 재확인 결과 기본 경로 `.graphrag/triples`는 `BuilderCli.runTrial`에만 있었다. `runSynthesizeTriple`은 `required(o, "--triple-store")`로 **하드 필수**였고(생략하면 기본 경로가 적용되는 게 아니라 실패), `build`의 `--triple-candidates`는 미지정 시 `null`(게이트 비활성)이라 기본 경로 개념 자체가 없었다. 즉 "CLI 실행 시 기본 경로가 적용된다"는 세 서브커맨드 중 하나에서만 참이었다. **조치:** 상수 `BuilderCli.DEFAULT_TRIPLE_STORE`와 해석 함수 `tripleStoreRoot(options)`/`tripleCandidatesRoot(options)`를 단일 소스로 두고 `build`/`synthesize-triple`/`trial` 셋 다 그것만 쓰게 했다(`--triple-candidates` > `--triple-store` > 기본 경로). `build`에서 루트가 항상 non-null이 되지만, 그 경로에 `promoted/` 후보가 없으면 `TriplePromotionGate.attempt`가 즉시 `NO_CANDIDATE`로 빠져 DB/HTTP 부작용이 0이므로 삼중을 안 쓰는 프로젝트의 관측 동작은 종전과 동일하다 — 달라지는 것은 "관례 경로에 삼중이 실제로 있으면 플래그 없이도 소비된다"는 점뿐이고, 이것이 수용기준이 요구한 계약이다. 검증: `TripleStoreCliContractTest`(4케이스 — 둘 다 생략/`--triple-store`만/두 플래그 병행/`--triple-candidates`만). 기본 경로가 **상대 경로**라 CLI를 실제 실행하면 작업 디렉토리에 디렉토리를 만들어 저장소를 오염시키므로, 해석 함수를 직접 검증하는 순수 단위 테스트로 두었다(부작용 0·결정적).

**수용기준 ②(e2e fixture 경로를 `--triple-store`로 전달) — 표기와 실제가 달랐고, 바로잡았다.** `[^req036-task18-partial]`은 이 기준을 충족했다고 적었으나 실제 `e2e/run-e2e.sh`는 `--triple-candidates $E2E/triples`를 넘기고 있었다 — 수용기준 문면은 `--triple-store`다. **조치:** 스크립트를 `--triple-store $E2E/triples`로 바꾸고(위 ①의 해석 규칙 덕분에 후보 루트도 같은 경로로 수렴), `e2e/run-e2e.sh`를 실제로 실행해 커밋된 fixture가 그 루트에서 소비되는 것을 로그로 확인했다: `triple adopted for post-api-transfers (REQ-018): <repo>/e2e/triples/post-api-transfers/promoted/cand-01`. 즉 `TripleStore`가 그 경로를 루트로 REQ-031 레이아웃(`<endpointId>/{base,promoted}/cand-NN` + `provenance-report.json`)을 그대로 따른다. **범위 명시:** 파이프라인의 `synthesize→trial` 절반을 **커밋된 fixture 경로에 대고** 돌리지는 않는다 — `synthesize-triple`은 그 루트에 `cand-NN`을 쓰므로 커밋된 fixture를 더럽히기 때문이다(의도적). 그 절반은 `TrialCliE2E#documentedPipelineOrderWorksWithoutExplicitReportFlag`가 run-scoped 루트 하나(`--triple-store`)로 `synthesize-triple → trial`을 완주해 동일 레이아웃을 검증한다.

**잔여 항목 재판정(범위 밖 — 이 REQ의 미충족이 아님).** (a) **attach 모드 외부 stub WireMock 라우팅**은 이 명세의 `Phase C: attach egress 라우팅`(🔵 out-of-scope) 소관이므로 REQ-036 분모에서 제외한다. (b) **`trial --graph` 자동 로드**는 REQ-036 설명·수용기준 어디에도 문면이 없다(`[^req036-task12-partial]` 자신이 "REQ-018 T3 파이프라인 통합 소관"이라고 적고 있다) — REQ-036이 규정하는 것은 `--triple-store`/`--triple-candidates` 플래그·기본 경로·e2e fixture 배치까지이므로 범위 밖으로 명시한다. 두 항목 모두 여전히 미구현이지만 REQ-036의 충족 여부와는 무관하다.

[^nested-list-schema-fix]: Task 18 선결 문제로 지적된 갭 — `TripleValidator.schemaViolationsForBody`의
`allowed` 집합은 `BodyShape.fields()`의 최상위 필드명만 담는데(`BodyShapeExtractor.flatten()`이
`List<DTO>` 컬렉션 필드를 원소 타입까지 전개하지 않고 top-level 리프 하나로 남기기 때문),
`collectLeafPaths`는 실제 후보 body에서 `items.sku`/`items.qty` 같은 중첩 dot-path 리프를 만들어
`allowed`와 항상 불일치 → 배열 바디 후보가 무조건 reject(STALE)됐다(Task 14/16이 이 갭 때문에 items를
후보에서 제거하고 우회한 사유가 이것). **수정:** `isAllowedPath(leafPath, allowed)`를 도입해
leafPath 전체 일치(기존 동작, 회귀 없음) 외에 "앞쪽부터 자라나는 접두 경로가 `allowed`의 원소와
일치"하면 허용하도록 확장했다 — top-level 필드가 `allowed`에 있으면 그 아래 중첩 서브트리(배열 원소
포함)를 인정하되, **접두사 자체가 `allowed`의 어떤 원소와도 일치하지 않는 완전히 새로운 top-level
필드는 여전히 reject**한다(REQ-011의 핵심 보장 — 미지 필드 거부 — 은 top-level 단위로 그대로 유지).
회귀 테스트 `TripleGateIT#req011_nestedListDotPathAcceptedWhenTopLevelFieldKnown`(허용 확인)과
`TripleGateIT#req011_unknownTopLevelPrefixStillRejectedEvenIfNested`(미지 top-level은 중첩이어도
여전히 reject 확인)를 추가했다 — 기존 13개 테스트 포함 `TripleGateIT` 15/15, `TripleSynthesizerIT`
8/8 GREEN(회귀 없음).

**알려진 한계(후속 보강 대상, 코드리뷰 Minor):** 이 완화는 top-level 접두사 일치만 확인할 뿐, known
top-level 필드 **아래의 중첩 필드명 자체는 무검증**이다 — `BodyShape`가 `List<DTO>` 원소의 중첩
구조(필드명·타입) 정보를 전혀 갖고 있지 않으므로(`BodyShapeExtractor.flatten()`이 원소까지
전개하지 않기 때문, 위 본문 참조), `items.anyRandomField` 같은 값도 이 게이트만으로는 막지 못한다.
근본 해결에는 `BodyShapeExtractor`가 컬렉션 원소 타입까지 전개하는 것(또는 동등한 원소 스키마 소스
도입)이 필요하며, 이는 이 task의 선언 파일 범위 밖이라 후속 보강 대상으로 남긴다.

[^req009-e2e-confirmed]: Task 18의 `TriplePromotionE2E#req018_adoptedTripleProducesSuccessExploredPath`가
실 SUT를 대상으로 한 완주 E2E에서 배열 원소 내부(`items[0].sku`/`items[0].qty`) 마커 위치에 사람이
채운 스칼라 값(`SKU-001`/`2`)이 base와의 마커-diff를 통과해 채택됨을 추가로 확인했다 — 기존
`TripleGateE2E`/`TripleGateIT`의 REQ-009 커버리지가 스칼라/단일 오브젝트 위주였던 데 비해, 배열
원소 내부 마커라는 구조적 변형까지 실 SUT E2E 레벨에서 검증된 것을 반영한다(새 REQ-ID 아님 — 기존
REQ-009 커버리지 보강).

[^req037-discovered-by-task18]: Task 18이 `post-api-transfers`의 REQ-018 완주 E2E(`e2e/run-e2e.sh`)를
처음으로 끝까지 실행하면서 발견했다 — `TransfersPostTest`의 negative-validation 파생 시나리오
2건(`s422e422_1`: amount를 base(=1) 대비 +1 경계인 `2`로 mutate, `s422e422_2`: `items` 필드 drop)이
생성 코드에 FK 부모 행 seed INSERT가 붙지 않아 404로 실패한다(기대는 422). **정확한 성격 규정:**
원인이 되는 `EndpointExplorationRunner`/`NegativeValidationSynthesizer`/test-generator 시드 추적
경로 자체는 이 Phase의 삼중 합성 파이프라인(T1/T2/T3) 도입 이전부터 존재했지만, "이미 실패하던
테스트"였던 적은 없다 — `TransfersPostTest` 클래스 자체가 이 task 이전에는 존재하지 않았다
(`post-api-transfers`가 REQ-028 outer red로 한 번도 2xx에 도달한 적이 없어 test-generator가 이
엔드포인트의 negative-validation 시나리오를 generate+run한 적이 한 번도 없었다). 즉 **원인 코드는
기존 서브시스템에 있었지만 그 결함을 최초로 관측 가능하게 만든 것은 Task 18**이다. 코디네이터
리뷰에서 이 잔여 실패를 "known-issue로 묵인"하지 않고 REQ-037로 명시 추적하기로 결정했다 — 삼중
합성 파이프라인 자체의 결함이 아니므로 Phase A 분모에서는 제외(🔵)하되, REQ-018/이 diff의 회귀로는
간주하지 않는다(코디네이터 리뷰: Approved).

Coverage: 34/37 green (92%), 3 partial(🟡 REQ-027, REQ-029, REQ-030 — 전부 manual Should) — target
100% (대상: Must 33 + 미연기 Should 4(REQ-027/029/030/037). Won't/Phase B·C: 🔵 분모 제외).

> **✅ Must 완료 정의 충족 / ⚠️ 전체 100%는 미충족(명시):** 이 명세의 완료 정의인 "**Must 100%
> green**"은 **충족됐다** — Must **33개**가 전부 🟢이다. Phase A 후속 작업이 마지막 두 Must를 해소했다:
> **REQ-032**(1/3, `ValueRef.derivedFrom` 스키마 확장 + `TripleSynthesizer` 채움 슬롯 통합 + CLI 오라클
> 배선, `[^derived-half]`/`[^req032-level]` 참조)와 **REQ-036**(2/3, `.graphrag/triples` 기본 경로를
> 세 서브커맨드 단일 소스로 통일 + `run-e2e.sh`를 `--triple-store`로 정정, `[^req036-done]` 참조).
>
> **분모가 34 → 33으로 바뀐 이유를 함께 읽어야 한다:** REQ-027(에이전트 완주 실증)은 원래 `우선순위:
> Must`로 정의돼 있었고 🟡다. 즉 재분류 전 실제 수치는 **Must 33/34**였으며, 이전 판(이 콜아웃의
> 직전 버전)이 REQ-027을 근거 없이 "manual Should"로 서술해 **34/34로 과대 표기한 것은 오류**였다.
> 이번에 REQ-027의 우선순위 자체를 Should로 재분류하고 그 근거를 `[^req027-reclass]`에 명시해
> 표기와 실제를 일치시켰다(요구사항 삭제·완화가 아니라 분류 정정 — REQ-027은 매트릭스와 전체
> 분모에 그대로 남는다).
>
> **남은 🟡는 REQ-027/029/030 3건이며 전부 manual Should**다 — 절차서는 작성돼 있고 실증 세션 실행만
> 남았다(`[^task19-manual-procedures]`). 따라서 "대상 37개 100% green"이라는 더 넓은 목표는 아직
> 충족되지 않았고, Phase A를 "전부 완료"로 선언하려면 그 3건의 manual 실증이 필요하다. Must 기준
> 완료와 전체 기준 완료를 구분해 읽어야 한다.

Task 15가
REQ-023/024/025를 🔴→🟢 전환(+3). Task 16이 REQ-022를 🔴→🟢 전환(+1). Task 17이 REQ-026을 🔴→🟢
전환(+1). Task 18이 REQ-018을 🟡→🟢 전환(+1)하고 REQ-036을 🔴→🟡 전환(부분 진전, 분자 미변경)
했으며 REQ-011 스키마 검증 갭(중첩 리스트 dot-path + stub headers)을 보강했다. Task 18이 이 과정에서
처음 노출시킨 별도 서브시스템 결함은 신규 **REQ-037**(🔵 out-of-scope)로 분리 추적한다(분모 영향
없음). **Task 19가 REQ-027/029/030을 🔴→🟡 전환**(절차서 작성 완료, 분자 미변경 — plan
"완료 정의"의 "CI 대상 REQ 전부 🟢" 요건은 이 3개 manual REQ를 제외하므로 이 전환으로 plan
완료 정의가 충족된다. 🟢 전환은 이 plan 범위 밖의 후속 실증 세션에서 절차서 실행 후 수행한다).

**REQ-037(해소됨 — 최종 fix wave):** 이전 상태였던 "`e2e/run-e2e.sh` 전체 실행 시
`TransfersPostTest`의 파생 변이 2건(`s422e422_1`/`s422e422_2`)이 404로 실패(`tests=85 failures=2`)"는
**해소됐다** — 현재 `e2e/run-e2e.sh`는 `tests=85 skipped=0 failures=0 errors=0`이다. 상세는
`[^req037-fixed]` 참조.

[^grb-trial-wired]: `GRB_TRIAL=off` 스위치는 Task 14(게이트 배선)까지 문서(design spec/plan)에만
명시되고 실제로는 `EndpointExplorationRunner`에 배선돼 있지 않았다(다른 `GRB_*` ablation 스위치 —
`GRB_RESPONSE_VARIANTS`/`GRB_KAFKA_DIFF`/`GRB_NEGATIVE_AUTH`/`GRB_NEGATIVE_VALIDATION`/
`GRB_REPRO_VERIFY` — 는 모두 `run()` 내부에서 `System.getenv` 체크가 있었으나 `GRB_TRIAL`만 없었음).
Task 16이 `EndpointExplorationRunner.tripleGateDisabledByAblation()`을 신설해 게이트 진입 조건
(`tripleCandidatesRoot != null && ...`)의 최상단에 배선했다 — env var(운영)와 system property(테스트,
`GRB_EXPLORER_EMPTY_BODY` 선례와 동일 관례) 양쪽을 확인해 같은 JVM 안에서 A/B 빌드를 만들 수 있게
했다. `TrialAblationE2E`가 실제로 STALE을 유발하는 promoted 후보를 배치한 채 `GRB_TRIAL=off`로
빌드해도 `trialCount=0`(게이트가 아예 호출되지 않음)임을 확인해, "우연히 후보가 없어 동일했다"가
아니라 스위치 자체가 게이트를 비활성화함을 고정했다.

[^task19-manual-procedures]: Task 19가 REQ-027(E2E-B1)/REQ-029(E2E-B2)/REQ-030(E2E-B3)의 실행
커맨드·판정 기준·기록 위치를 이 요구사항명세의 수용기준 문구 그대로 고정한 절차서
`docs/superpowers/reports/2026-07-26-triple-synthesis-manual-evidence.md`를 작성했다. 세
절차 모두 아직 **실행되지 않았다** — 각 절차의 "완료 후 처리"에 명시된 대로, 후속 세션이 그
절차서를 실제로 수행하고 판정 결과를 기록해야만 이 REQ들이 🟢로 전환된다. 절차서 작성 과정에서
확인한 구현 세부 사항 하나를 여기 기록한다: 독립 `trial` CLI(`BuilderCli.runTrial`)는
`TrialRunner`를 6-arg 생성자로 생성해 `attachMode=false`로 고정돼 있어, REQ-023(attach 이중
opt-in)이 이 CLI 경로에서는 애초에 활성화되지 않는다 — attach 이중 opt-in이 실제로 배선되는
경로는 `build --attach --triple-candidates <dir> [--attach-allow-seed]
[--confirm-non-production]` 뿐이다(`EndpointExplorationRunner` → `TriplePromotionGate` →
`TrialRunner`의 9-arg 생성자). E2E-B3 절차서는 이를 반영해 독립 `trial` CLI가 아니라 `build
--attach`로 재확인 절차를 구성했다. `docs/coverage-progress.md`의 "Phase A" 절과
`docs/03-graph-rag-builder.md`의 "삼중 합성" 절도 이 task에서 함께 동기화했다.

## design spec E2E ↔ REQ 매핑

| design spec E2E | REQ |
|---|---|
| E2E-A1 | REQ-001, REQ-034 (+REQ-002~004·032: 별도 integration 테스트, E2E-A1과 동일 golden 소스 재사용) |
| E2E-A2 | REQ-005~008, REQ-033 |
| E2E-A3 | REQ-013, REQ-018, REQ-031, REQ-036 |
| E2E-A4 | REQ-009~012 |
| E2E-A5 | REQ-014, REQ-016, REQ-022 |
| E2E-A6 | REQ-020, REQ-035 |
| E2E-B1 | REQ-027 (REQ-026 구조 검사는 unit 별도) |
| E2E-B2 | REQ-029 |
| E2E-B3 | REQ-030 |
| (선행 전제) | REQ-028 |

[^c4-readjudication]: **최종 whole-branch 리뷰(C4, 판정 Not ready) 이후 재판정.** 리뷰는 REQ-009/012의
"🟢"가 실제로는 무력화돼 있고 REQ-001/011/013의 "🟢"도 그 위에 서 있다고 지적했다. 확인된 결함과
이 fix wave의 조치·재판정은 다음과 같다.

- **결함 C4-1 (REQ-009/012 무력화):** `TripleValidator.extractRows`가 `rows.put(table, row)`로
  **테이블당 마지막 INSERT 한 행만** 보관했다. 후보가 base와 동일한 행 *앞에* 자기 행을 끼워 넣으면
  테이블 집합·컬럼 집합·값 비교를 모두 통과하면서 그 행이 `TrialRunner`로 실행되고 ADOPT 시 영속
  삽입됐으며, `markerFilledValues`에도 들어가지 않아 PII 스캔(REQ-012)까지 우회했다.
  **조치:** `extractRows`를 `table -> List<SeedRow>`로 바꾸고 테이블별 **행 수와 순서**까지 동일성을
  요구한다. **재판정: REQ-009/012 🟢 유지** — 회귀 테스트 `TripleGateIT#req009_extraSeedRowInsertedBeforeMatchingRowRejected`,
  `#req009_extraSeedRowAppendedAfterMatchingRowRejected`, `#req009_seedRowOrderSwapRejected`,
  `#req009_multipleRowsPerTableUnchangedAccepted`(회귀 0 확인)로 고정했다.
- **결함 C4-2 (REQ-009/011/013 — 컬럼 순서 + 데이터 손실):** 컬럼 비교가 `Set.equals`라 순서를
  무시했는데 정리 DELETE는 `columns.get(0)`을 PK로 **가정**했다. 후보가 `(id, balance_amount)`를
  `(balance_amount, id)`로 뒤집으면 T1을 통과한 뒤 `DELETE ... WHERE balance_amount = 1`이 나가
  **조건에 맞는 모든 행**이 삭제됐고, DELETE가 성공하므로 REQ-024 잔존행 차단도 발화하지 않았다.
  **조치:** (a) 컬럼을 **순서 포함 리스트**로 비교(`TripleGateIT#req009_seedColumnOrderSwapRejected`),
  (b) 정리 키를 후보 텍스트가 아니라 **DB 카탈로그의 PK 사실**로 해석하고 값을 `PreparedStatement`로
  바인딩하며, PK를 결정할 수 없으면(PK 없는 테이블/모호한 테이블명/PK 컬럼 미포함) **DB 쓰기 전에
  차단**한다(`SEED_CLEANUP_UNRESOLVABLE`). **재판정: REQ-009/011/013 🟢 유지** — 회귀는
  `TrialSeedCleanupIT`(5케이스: PK 기준 정리·PK 미상 차단·인용 식별자 차단·컬럼 목록 없는 INSERT
  차단·통상 후보 회귀 0).
- **결함 C4-3 (REQ-013 — 독립 CLI가 T1을 통째로 우회):** `BuilderCli.runTrial`이
  `TripleValidator.validate`를 전혀 호출하지 않았고, 정리 DELETE가 식별자를 문자열 결합했으며,
  컬럼 목록 없는 `INSERT INTO t VALUES (...)`는 추적 가드를 빠져나가 정리 대상에서 누락됐다.
  **조치:** (a) `runTrial`이 후보마다 T1을 호출하고 통과한 후보만 시험한다(거부 시 `T1_REJECTED`
  다이제스트로 `failed/` 격리, DB/HTTP 부작용 0), (b) 정리 DELETE는 파라미터 바인딩 + 카탈로그
  식별자 + 안전 식별자 정규식만 사용, (c) 컬럼 목록 없는 INSERT를 `SeedSqlWhitelist` allowlist에서
  reject. **재판정: REQ-013 🟢 유지** — `TrialCliE2E#t1GateRejectsNonMarkerChangeInStandaloneCli`,
  `#missingProvenanceReportFailsClosed`로 고정. 이 변경으로 **provenance 리포트가 이 CLI의 필수
  입력**이 됐다(화이트리스트 허용 테이블 집합의 유일한 출처) — `--provenance-report` 또는
  `<candidates-root>/<endpointId>/provenance-report.json`이 없으면 후보를 하나도 시험하지 않고
  즉시 실패한다.
- **REQ-001 재판정: 🟢 유지(변경 없음).** 리뷰가 REQ-001을 지목한 것은 C4-1/2가 T1 게이트 전반의
  신뢰를 흔든 데 따른 연쇄 의심이며, provenance 산출 경로(`ProvenanceIndexer`) 자체의 결함이
  보고되지는 않았다. 이 fix wave는 그 경로를 건드리지 않았고 `ProvenanceCliE2E#REQ-001`(golden,
  태그 없음 → CI `unit` 샤드에서 실행됨)이 그대로 green이다. 근거가 없는 강등은 하지 않는다.
- **REQ-011 잔여 카브아웃(🟢 유지, 명시):** 독립 `trial` CLI에는 그래프 자산이 없어
  `BodyShape.empty()`가 넘어가므로 **REQ-011 중 body 필드 스키마 검증만 이 CLI 경로에서 skip**된다
  (마커-diff·seed 화이트리스트·PII·stub 스키마 검증은 전부 적용). 통합 `build` 경로는 실제
  `BodyShape`를 갖고 있어 이 갭이 없고, REQ-011의 수용 테스트(`TripleGateIT#REQ-011`)도 실제
  `BodyShape`로 검증한다. `trial-loop/SKILL.md`에 이 카브아웃을 명시했다.

[^req037-fixed]: **최종 fix wave에서 해소.** 근본 원인은 `FixtureComposer.lookupSucceeded`가 "조회
성공"의 증거를 **그 path 자신의 outcome**에서만 찾은 것이다 — 파생 시나리오(원본 happy body를
변이해 만든 422 등)는 자기 응답이 FAILURE라 부모 행 시드를 만들지 않았고, 그래서 생성 TC가 시드
없이 실행돼 기대한 422 대신 404가 났다. 반면 클래스 주석이 선언한 의도는 "404류는 데이터 부재가
재현 조건(seed 금지), 409류는 존재가 전제(seed 필요)"였으므로 구현이 선언된 의도보다 좁았다.
**수정:** `Generator.provenExistingKeys(endpointId)`가 같은 endpoint의 **2xx(SUCCESS) 시나리오**가
SELECT 바인딩으로 존재를 증명한 **(테이블, 컬럼, 값) 조합** 집합을 계산해 `FixtureComposer.compose`에
넘기고, 파생 시나리오는 같은 자리·같은 키 값에 대해 그 부모 행 시드를 **상속**한다. 상태코드 기반
휴리스틱(예: "404가 아니면 시드")은 쓰지 않았다 — 200-wrapped 에러 엔벨로프(REQ-005)를 깨뜨린다.
새 오버로드는 기본값이 빈 집합이라 기존 호출부는 동작이 그대로다.

**REQ-005 회귀 범위(리뷰 N2에서 정정 — 초판의 "회귀가 없다"는 과장이었다):** 최초 구현은 상속
매칭이 **값 문자열 only**였고, 상속 자체가 `lookupSucceeded`의 outcome 판정을 우회하는 예외
경로였다. 그래서 같은 endpoint에 SUCCESS와 200-엔벨로프 FAILURE가 공존하면 엔벨로프 path에도
시드가 붙어 REQ-005가 실제로 깨졌다 — 당시 단위 테스트가 `FixtureComposer`를 **빈 증명 집합**으로
직접 호출해 이 상호작용을 전혀 커버하지 못했기 때문에 드러나지 않았다. 두 방향으로 좁혀 해소했다:
(a) 매칭 단위를 `FixtureComposer.ProvenKey(table, column, value)`로 축소, (b) `inheritsProvenSeed`가
**2xx + outcome=FAILURE**(에러 엔벨로프) path를 상속 대상에서 제외. REQ-037이 겨냥한 파생
시나리오는 전부 비-2xx라 (b)에 걸리지 않는다.

**검증:** 단위 `LookupSucceededOutcomeTest#derivedFailurePathInheritsSeedFromProvenSiblingKeyValue`
(증거 없으면 시드 안 함 + 증거 있으면 시드함 + 자리가 다른 증거는 상속 안 함) +
**Generator 레벨** `GeneratorProvenSeedInheritanceTest`(같은 endpoint에 SUCCESS/200-엔벨로프
FAILURE/422 파생/교차-테이블 409가 섞인 `fixture-req005-graph`로 REQ-005·REQ-037을 동시에 고정)
+ `e2e/run-e2e.sh` 실행 결과 `tests=85 skipped=0 failures=0 errors=0`(직전 `failures=2`에서 전환).
생성 코드에서 `s422e422_1`/`s422e422_2` 모두 `INSERT INTO fund_accounts ... / deferDelete`가 붙은
것을 확인했다.
