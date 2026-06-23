# LlmOracle(LLM 값 오라클) 요구사항명세

> 출처(design spec): docs/superpowers/specs/2026-06-23-llm-oracle-design.md
> 완료 정의(DoD): 커버리지 대상 요구사항(Must + 미연기 Should)이 모두 ≥1개의 통과 수용
> 테스트를 가짐 (대상 매트릭스 전부 green). + 전 모듈·e2e 회귀 GREEN, LLM off 경로 불변.

## 요구사항 목록

### REQ-001 — LlmOracle는 InputOracle SPI를 구현한다
- 유형: Functional
- 우선순위: Must
- 설명: `LlmOracle`는 `io.graphrag.builder.oracle.InputOracle`를 구현하고 `name()="llm"`,
  `analyze(SutCode)`로 글로벌 `InputCandidates`(strings 채널만)를 반환한다.
- 수용기준:
  - Given LlmOracle 인스턴스, When `name()` 호출, Then `"llm"` 반환.
  - Given FakeValueClient가 필드값을 돌려주는 LlmOracle, When `analyze(sut)`, Then 해당 필드가
    `InputCandidates.strings`에 누적되고 numeric/tuples/reals 채널은 비어 있다.
- 검증 레벨: integration (unit)

### REQ-002 — 결정성: 동일 입력은 동일 출력(캐시 하드 보장)
- 유형: Non-functional
- 우선순위: Must
- 설명: 같은 (endpoint.id + 핸들러 본문 + 필드셋 + 모델ID)면 LLM 호출 없이 캐시에서 동일 값을
  돌려준다. 결과 컬렉션은 결정적 정렬(TreeMap/TreeSet).
- 수용기준:
  - Given 동일 SutCode로 LlmOracle.analyze를 2회 호출(캐시 존재), When 두 결과 비교, Then 완전 동일.
    (test: `LlmOracleTest#deterministicOutputOnSameInput`)
  - Given 캐시 hit, When analyze, Then `LlmValueClient.generate`가 **호출되지 않는다**(스파이 0회).
    (test: `LlmOracleTest#cacheHitSkipsClientCall`)
- 검증 레벨: integration (unit)

### REQ-003 — 캐시 키는 핸들러 본문 변경 시 무효화된다
- 유형: Functional
- 우선순위: Must
- 설명: 캐시 키 = `sha256(endpoint.id + 핸들러 본문 소스 + 정렬 필드셋(name:type) + modelId)`.
  필드 순서·무관 커밋엔 안정, 핸들러 본문/필드/모델 변경엔 키 변화.
- 수용기준:
  - Given 두 입력이 필드 순서만 다름, When 키 계산, Then 동일 키.
  - Given 핸들러 본문 문자열이 다름, When 키 계산, Then 다른 키.
  - Given 모델 ID가 다름, When 키 계산, Then 다른 키.
- 검증 레벨: integration (unit)

### REQ-004 — 캐시 read(classpath) / write(filesystem) 라운드트립
- 유형: Functional
- 우선순위: Must
- 설명: `LlmValueCache`는 `src/main/resources/llm-oracle-cache/<key>.json`을 classpath로 읽고,
  miss 시 filesystem 소스트리에 쓴다.
- 수용기준:
  - Given 캐시에 쓴 직후, When 같은 키로 읽기, Then 동일 `LlmFieldValues` 반환.
  - Given 존재하지 않는 키, When 읽기, Then 빈 결과(Optional.empty 등) — 예외 없음.
- 검증 레벨: integration (unit)

### REQ-005 — CI 오프라인: API 키 없고 캐시 miss면 skip(빌드 중단 없음)
- 유형: Non-functional
- 우선순위: Must
- 설명: `ANTHROPIC_API_KEY` 부재 + 캐시 miss면 해당 엔드포인트 기여를 비우고 진행(`log.info`).
  `AnthropicValueClient`는 키 없어도 **생성 시 실패하지 않는다**(lazy; 실제 generate 호출 때만 필요).
- 수용기준:
  - Given API 키 없음 + 캐시 miss, When analyze, Then 예외 없이 빈 strings 기여 + 진행.
  - Given API 키 없음, When `AnthropicValueClient.fromEnv(model)` 생성, Then 예외 없이 객체 생성.
- 검증 레벨: integration (unit)

### REQ-006 — 정적 필드선별: 엄격 검증 필드만, 0건이면 skip
- 유형: Functional
- 우선순위: Must
- 설명: `EndpointFieldSelector`는 @Pattern/@Email String 필드, enum-스타일 도메인 코드 후보만
  선별한다. 순수 숫자·Java enum 타입·이미 리터럴 추출된 필드는 제외. 선별 0건이면 LLM 미호출.
  도메인 코드 키워드 목록(`status`/`type`/`code`/`tier`/`grade` 등)은 `EndpointFieldSelector`의
  고정 상수.
- 수용기준:
  - Given @Pattern·@Email 필드와 순수 int 필드가 섞인 엔드포인트, When 선별, Then String 검증
    필드만 포함, int 필드 제외.
  - Given 필드명이 `status`/`tier`이고 String 타입이며 Java enum이 아닌 필드, When 선별, Then 포함.
  - Given Java enum 타입 필드, When 선별, Then 제외.
  - Given 선별 결과 0건인 엔드포인트, When analyze, Then 그 엔드포인트에 대해 `generate` 미호출.
- 검증 레벨: integration (unit)

### REQ-007 — ShapeGate 그라운딩: 존재·String 타입만 수용, 부적합 폐기+로그
- 유형: Functional
- 우선순위: Must
- 설명: LLM 후보를 BodyShape에 게이트 — BodyShape에 없는 필드, `java.lang.String`이 아닌 필드의
  후보는 폐기하고 `log.warn`. 통과분만 strings에 반영.
- 수용기준:
  - Given BodyShape에 없는 필드명 후보, When 게이트, Then 폐기(결과 미포함).
  - Given non-String(int/long/double) 필드 후보, When 게이트, Then 폐기.
  - Given 존재하는 String 필드 후보, When 게이트, Then 수용.
- 검증 레벨: integration (unit)

### REQ-008 — HandlerSourceExtractor: 핸들러 메서드 본문 추출
- 유형: Functional
- 우선순위: Must
- 설명: `(handlerClass, handlerMethod)` → 메서드 본문 소스 텍스트(Spoon). 미존재 메서드는 graceful.
- 수용기준:
  - Given srcDir + 존재하는 핸들러, When extract, Then 메서드 본문 텍스트(비어있지 않음) 반환.
  - Given 존재하지 않는 메서드, When extract, Then 빈/Optional 반환(예외 없음).
- 검증 레벨: integration (unit)

### REQ-009 — BuilderCli 플래그 게이트(`--llm-oracle`)와 off 무변경
- 유형: Functional
- 우선순위: Must
- 설명: `--llm-oracle` 지정 시에만 `BuildConfig.llmOracle()`가 true가 되어 `explore()`에서
  LlmOracle를 merge. `--llm-model`로 모델 선택(기본 `claude-haiku-4-5-20251001`). 미지정 시 no-op.
  **전제**: `BuildConfig` record에 `boolean llmOracle, String llmModel` 필드 신규 추가 +
  기존 생성자/팩토리(하위 호환 오버로드 포함) 갱신 필요.
- 수용기준:
  - Given `--llm-oracle` 없는 인자, When parseArgs→BuildConfig, Then `llmOracle()==false`이고
    LlmOracle 미생성.
  - Given `--llm-oracle --llm-model claude-sonnet-4-6`, When BuildConfig, Then `llmOracle()==true`,
    `llmModel()=="claude-sonnet-4-6"`.
  - Given `--llm-oracle`만, When BuildConfig, Then `llmModel()=="claude-haiku-4-5-20251001"`.
- 검증 레벨: integration (unit)

### REQ-010 — 아키텍처 경계: LLM 코드는 index 패키지 무오염
- 유형: Non-functional
- 우선순위: Must
- 설명: anthropic/openai/HttpClient/okhttp3는 `io.graphrag.builder.index`에 들어가지 않는다(LLM
  코드는 `io.graphrag.builder.oracle`). 기존 `NoLlmDependencyTest`를 재사용·유지.
- 수용기준:
  - Given 구현 완료, When `NoLlmDependencyTest` 실행, Then GREEN(index 패키지에 금지 import 없음).
- 검증 레벨: integration (unit, arch test)
- 비고: 기존 `NoLlmDependencyTest` 코드 주석은 `// REQ-021`(타 명세 method1-tool-gaps의 번호).
  그 추적성을 깨지 않도록 **코드 주석은 그대로 두고**, 본 명세에선 REQ-010이 이 동일 테스트를
  재사용함을 매트릭스에 기록한다(주석 재번호 금지).

### REQ-011 — E2E 회귀: LLM off 경로 불변·GREEN
- 유형: Functional
- 우선순위: Must
- 설명: `--llm-oracle` 미지정 시 빌더 실행 결과(InputCandidates·생성물·기존 분기 커버리지)가
  변경 전과 동일. 전 기존 테스트 GREEN.
- 수용기준:
  - Given fixture 포함 SUT, When `--llm-oracle` 없이 빌드, Then 기존 동작/생성물 불변 + 기존 e2e
    전부 GREEN.
  - 단, REQ-012 fixture 엔드포인트 추가로 `BuilderIntegrationTest`의 endpoint-id `containsExactly`
    목록은 새 엔드포인트를 포함하도록 **갱신**된다 — 이는 회귀가 아닌 **의도된 변경**으로 처리.
- 검증 레벨: E2E black-box (BuilderIntegrationTest 류)
- 실행 환경: Docker 필요, `sut.jar`/`sut.src` system property 필요(`@EnabledIfSystemProperty` 가드).

### REQ-012 — E2E 효과: @Pattern 게이트 깊은 분기를 LLM on(캐시)으로 도달, 커버리지 증가
- 유형: Functional
- 우선순위: Must
- 설명: order-service에 @Pattern으로 게이트되는 깊은 분기 fixture 엔드포인트를 추가. LLM off면
  happy 합성이 정규식 불충족→400으로 깊은 분기 미도달; LLM on(커밋된 캐시값)이면 정규식+도메인
  접두 충족값으로 깊은 분기 도달 → 해당 엔드포인트 branch 커버리지가 off 대비 증가. 캐시로
  오프라인·결정적.
- 사전 조건: (a) order-service에 fixture 엔드포인트(예: `POST /api/coupons`,
  `@Pattern(regexp="[A-Z]{4}-\\d{4}") String couponCode`, 핸들러에 `if (couponCode.startsWith("GOLD"))`
  깊은 분기) 신규 추가, (b) 그 엔드포인트의 캐시 파일(값 `"GOLD-1234"`)이
  `src/main/resources/llm-oracle-cache/`에 커밋되어 있어야 함(핸들러 본문 해시 키 일치).
- 측정 메커니즘: 기존 e2e 패턴(`ExploredPath.branchesTaken()`/status 단언)을 따른다. Jacoco 파일
  diff가 아님.
- 수용기준:
  - Given fixture + 커밋 캐시, When `--llm-oracle` **off**로 빌드, Then 해당 엔드포인트 경로에
    gold-tier 분기(`branchesTaken`)가 **없음**(정규식 불충족 400).
  - Given fixture + 커밋 캐시, When `--llm-oracle` **on**으로 빌드(API 무호출), Then 같은 엔드포인트
    경로에 gold-tier 분기(`branchesTaken`)가 **포함**됨 → off 대비 covered 분기 ↑.
  - Given API 키 없음, When on 실행, Then 캐시만으로 동작(네트워크 호출 0).
- 검증 레벨: E2E black-box
- 실행 환경: Docker 필요, `sut.jar`/`sut.src` system property 필요(`@EnabledIfSystemProperty` 가드).

### REQ-013 — Anthropic SDK structured 호출(temperature 0, 모델 핀)
- 유형: Functional
- 우선순위: Should
- 설명: `AnthropicValueClient`는 structured output(JSON schema 자동 도출) + temperature 0 +
  지정 모델 ID로 단일 호출을 구성한다.
- 수용기준:
  - Given 모델 ID와 LlmRequest, When 호출 파라미터 구성, Then temperature 0·해당 모델 ID·
    structured 스키마가 설정됨(파라미터 단위 단언, 실 API 무호출).
- 검증 레벨: integration (unit)

### REQ-014 — 프롬프트 주입·비용 완화
- 유형: Non-functional
- 우선순위: Should
- 설명: 핸들러 소스는 **메서드 본문만** 포함하고, 필드/제약은 structured 파라미터로 분리한다.
  temperature 0 + structured output으로 출력 형태를 고정한다.
- 수용기준:
  - Given LlmRequest 구성, When 검사, Then handlerSource는 메서드 본문(전체 파일 아님)이며 필드·제약은
    별도 구조화 필드로 분리되어 있다.
- 검증 레벨: integration (unit)

### REQ-015 — Best-effort 에러 처리: 실패해도 빌드 중단 없음
- 유형: Non-functional
- 우선순위: Must
- 설명: LLM 경로의 런타임 실패는 빌드를 깨지 않는다 — (a) API 호출 실패(네트워크/rate limit/
  refusal/스키마 불일치 최종 실패)는 해당 엔드포인트만 skip + `log.warn` 후 나머지 진행, (b) 캐시
  write 실패(권한 등)는 `log.warn` 후 그 값은 사용하되 캐시 미기록.
- 수용기준:
  - Given `generate`가 예외를 던지는 FakeValueClient, When analyze, Then 예외 전파 없이 그 엔드포인트
    기여만 비우고 나머지 엔드포인트는 정상 누적.
  - Given 쓰기 불가 캐시 경로, When miss 후 write 시도, Then 예외 없이 `log.warn` + 해당 값은
    analyze 결과에 포함(캐시 미기록 허용).
- 검증 레벨: integration (unit)

### REQ-016 — 교체 가능 LLM 백엔드 셀렉터(`--llm-backend api|bedrock|cli`)
- 유형: Functional
- 우선순위: Should
- 설명: `--llm-backend`로 `LlmValueClient` 구현을 선택한다 — `api`(1st-party Anthropic, 기본),
  `bedrock`(AWS Bedrock), `cli`(로컬 CLI). 모두 동일 `LlmValueClient` 인터페이스 뒤. 미지정 시 `api`.
- 수용기준:
  - Given `--llm-backend` 미지정, When BuildConfig, Then backend=="api".
  - Given `--llm-backend bedrock`, When 클라이언트 선택, Then `AnthropicValueClient.bedrock(...)` 사용.
  - Given `--llm-backend cli`, When 클라이언트 선택, Then `CliValueClient` 사용.
  - Given 알 수 없는 값, When 선택, Then 명확한 예외(또는 api 폴백 + 경고).
- 검증 레벨: integration (unit)

### REQ-017 — CLI 백엔드(`claude`/`cursor-agent`/`agy` `-p`)
- 유형: Functional
- 우선순위: Should
- 설명: `CliValueClient`는 `--llm-cli`로 지정한 바이너리를 비대화로 실행해 프롬프트를 보내고 stdout에서
  `{"fields":[...]}` JSON을 추출한다. CLI별 인터페이스가 달라 분기한다 — `claude`/`cursor-agent`/`agy`는
  `-p --model`, `kiro-cli`는 `chat --no-interactive --model --trust-tools=`. JSON 추출은 TUI 색코드·서문이
  섞여도 균형 `{..}`만 골라 파싱. API 키 불필요(CLI 로그인 자격증명).
- 수용기준:
  - Given 바이너리·모델·프롬프트, When 커맨드 구성, Then `[<bin>, -p, --model, <model>]` + 프롬프트가
    인자/stdin로 전달되는 결정적 커맨드.
  - Given CLI가 래핑/서문과 함께 `{"fields":...}`를 출력, When 파싱, Then 필드 값 추출(관용적 JSON 추출).
- 검증 레벨: integration (unit; 실제 프로세스 spawn은 CI 비실행)

### REQ-018 — Bedrock 백엔드(SDK Mantle, lazy, 모델 접두)
- 유형: Functional
- 우선순위: Should
- 설명: `AnthropicValueClient.bedrock(modelId)`는 `BedrockMantleBackend`로 SDK 클라이언트를 lazy
  구성하고 모델 ID에 `anthropic.` 접두를 적용한다(예 `anthropic.claude-haiku-4-5`). AWS 자격증명 체인
  사용(ANTHROPIC_API_KEY 불요). 키/자격 없어도 생성 실패 금지(lazy).
- 수용기준:
  - Given `bedrock("claude-haiku-4-5-20251001")`, When 생성, Then 예외 없이 생성, 해석 모델 ID가
    `anthropic.` 접두를 가짐.
- 검증 레벨: integration (unit; 실제 호출 CI 비실행)

### REQ-019 — 입력면 확장: PATH/QUERY 파라미터까지 선별
- 유형: Functional
- 우선순위: Should
- 설명: LLM selector 대상을 바디 필드(BODY/FORM, 기존)에 더해 **PATH/QUERY 파라미터**로 확장한다
  (바디 없는 read 엔드포인트 포함). 파라미터는 @Pattern 추출 대상이 아니므로 도메인코드 이름
  휴리스틱으로 선별하고, ShapeGate 그라운딩은 바디 필드 ∪ 파라미터의 유효 입력면에 대해 수행한다.
- 수용기준:
  - Given 바디 없는 GET 엔드포인트에 도메인코드명 String 쿼리 파라미터(예 `status`), When analyze,
    Then 그 파라미터가 선별되어 `strings`에 기여된다.
  - Given int PATH 파라미터, When 선별, Then 제외(비-String).
- 검증 레벨: integration (unit)

## 추적 매트릭스

| REQ-ID | 요구사항 | 수용 테스트 | Level | Status |
|--------|----------|-------------|-------|--------|
| REQ-001 | InputOracle 구현·strings 기여 | `LlmOracleTest#implementsSpiAndContributesStringsOnly` | unit | 🟢 green |
| REQ-002 | 결정성 | `LlmOracleTest#deterministicOutputOnSameInput` | unit | 🟢 green |
| REQ-002 | 캐시 hit 미호출 | `LlmOracleTest#cacheHitSkipsClientCall` | unit | 🟢 green |
| REQ-003 | 캐시 키 무효화 규칙 | `LlmValueCacheTest#keyStableUnderFieldOrder` + `#keyChangesOnBodyModelOrEndpoint` | unit | 🟢 green |
| REQ-004 | 캐시 read/write 라운드트립 | `LlmValueCacheTest#writeThenReadRoundTripsFromFilesystem` + `#readMissReturnsEmpty` | unit | 🟢 green |
| REQ-005 | CI 오프라인 skip·lazy 클라이언트 | `LlmOracleTest#noKeyCacheMissSkips` + `AnthropicValueClientTest#constructsWithoutApiKey` | unit | 🟢 green |
| REQ-006 | 정적 필드선별·0건 skip | `EndpointFieldSelectorTest#selectsPatternEmailStringSkipsNumeric` + `#selectsDomainCodeKeywordStringExcludesEnumType` + `#emptyWhenNothingStrict` | unit | 🟢 green |
| REQ-007 | ShapeGate 그라운딩 | `ShapeGateTest#acceptsExistingStringField` + `#rejectsNonExistentField` + `#rejectsNonStringField` | unit | 🟢 green |
| REQ-008 | 핸들러 본문 추출 | `HandlerSourceExtractorTest#extractsMethodBody` + `#missingMethodReturnsEmpty` | unit | 🟢 green |
| REQ-009 | 플래그 게이트·off no-op | `BuilderCliLlmFlagTest#flagAbsentMeansOff` + `#flagAndModelParse` + `#defaultModelWhenFlagOnly` | unit | 🟢 green |
| REQ-010 | index 패키지 무오염 | `NoLlmDependencyTest#indexers_haveNoLlmOrDirectHttpClientImports` (기존) | unit/arch | 🟢 green |
| REQ-011 | E2E LLM off 회귀 불변 | `LlmOracleE2E#offPathDoesNotReachGoldBranch` (+기존 e2e suite, `BuilderIntegrationTest`) | E2E | 🟢 green |
| REQ-012 | E2E @Pattern 깊은분기 커버리지 증가 | `LlmOracleE2E#cachedLlmOnReachesGoldBranch` | E2E | 🟢 green |
| REQ-013 | SDK structured·temp0·모델핀 | `AnthropicValueClientTest#prepareUsesTemperature0ModelPinAndStructuredPrompt` + `#modelIdPinned` + `#parsesExpectedJsonShape` | unit | 🟢 green |
| REQ-014 | 프롬프트 주입·비용 완화 | `LlmRequestTest#carriesBodyOnlySourceAndSeparatedFieldConstraints` | unit | 🟢 green |
| REQ-015 | best-effort 에러 처리 | `LlmOracleTest#clientFailureSkipsEndpointOnly` + `LlmValueCacheTest#writeFailureIsSwallowed` | unit | 🟢 green |
| REQ-016 | 백엔드 셀렉터 api/bedrock/cli | `LlmBackendSelectorTest#selectsByName` + `BuilderCliLlmFlagTest#backendFlagParses` | unit | 🟢 green |
| REQ-017 | CLI 백엔드(claude/cursor-agent/agy/kiro-cli) | `CliValueClientTest#buildsPrintCommand` + `#buildsKiroChatCommand` + `LlmJsonTest#extractsFromKiroStyleAnsiWrappedOutput` | unit | 🟢 green |
| REQ-018 | Bedrock 백엔드(lazy, 접두) | `AnthropicValueClientTest#bedrockFactoryPrefixesModelLazily` | unit | 🟢 green |
| REQ-019 | 입력면 확장(PATH/QUERY) | `LlmOracleTest#selectsDomainCodeQueryParamOnReadEndpoint` | unit | 🟢 green |

Coverage: 19/19 green (100%) — 대상: Must 13 + 미연기 Should 6. Could/Won't: 없음.
검증: 전체 회귀(`./gradlew test`) 471 tests, 비-E2E 전원 green; `LlmOracleE2E`(Docker) off/on 2건 green
(캐시값 `GOLD-1234`가 `CouponController.redeem` 깊은 분기 도달, API 무호출).
후속(비목표, 매트릭스 제외): 커버리지 피드백 재질의, numeric/reals LLM 기여, 자동 모델
에스컬레이션, GRB_LLM_ORACLE ablation 자동화.
