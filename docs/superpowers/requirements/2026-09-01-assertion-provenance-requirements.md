# 요구사항명세 — 어설션 provenance 확장 (A·B·D)

작성: 2026-09-01. 배경: 생성 테스트 85건 실측에서 `notNullValue` 강등이 과다함이 확인됨
(실패 경로 47건 × 엔벨로프 4필드, batch류 입력 유도 카운트). 뮤테이션 실증에서 응답값을
고정한 어설션은 결함을 정확히 잡았고(status PENDING→CONFIRMED 3건 검출), 상태코드만 고정한
실패 경로는 겹가드 마스킹(nights 경계 →31)을 잡지 못했다. 본 명세는 **"결정성을 증명한 값만
고정한다" 철학을 유지한 채** 증명 경로 3종을 추가한다. 스냅샷(관측값 무증명 고정)은 도입하지
않는다.

## 범위

- 도구 2(test-generator): 합성 후처리 단계 `AssertionProvenanceUpgrader` 신설 — `Generator`가
  `FixtureComposer.compose()` 결과의 assertions에 대해 `(ExploredPath, Endpoint, 생성 시점
  resolvedRequestPath)`를 넘겨 호출한다. notNullValue() 매처만 승격하며, 어설션 추가·기존 구체
  매처 변경은 하지 않는다(REQ-006 에러 계약 분기와의 충돌 원천 차단). reason phrase는 내장
  상수 맵(RFC 9110)으로 판정한다. (REQ-A, REQ-B, REQ-D 생성부)
- 도구 1(graph-rag-builder) 정적 인덱싱: 핸들러 소스의 예외 메시지 리터럴 추출 → `graph.json` 반영 (REQ-D 캡처부)
- shared-model: `Endpoint` 레코드에 `errorMessageLiterals` 필드 추가 — compact 생성자에서
  null→빈 목록 정규화, 기존 7-arg/8-arg 생성자는 오버로드로 유지(구 graph.json 역직렬화 호환) (REQ-D)
- 정적 인덱스 캐시: `IndexCache.SCHEMA_VERSION` 3→4 범프 — 구 캐시 복원 시
  `errorMessageLiterals`가 빈 목록으로 굳는 stale을 방지 (REQ-D)
- 두 도구의 결정성 계약(같은 입력 → 같은 출력)은 불변. LLM 없음 불변.

## REQ-A: 프레임워크 에러 엔벨로프 계약 provenance

Spring Boot 기본 에러 바디 `{timestamp, status, error, path}`는 값이 프레임워크 계약으로
결정된다: `status`==HTTP 상태코드, `error`==해당 코드의 reason phrase, `path`==요청 URI.

- **Given** outcome=FAILURE인 path의 `sampleResponse`가 위 4필드를 모두 갖고,
  **When** 테스트를 합성하면,
  **Then** 다음을 만족할 때 각 필드에 구체 매처를 낸다:
  - `status`: 관측값==expectedStatus → `equalTo(<expectedStatus>)` (정수 매처)
  - `error`: 관측값==표준 reason phrase(내장 코드→phrase 맵) → `equalTo("<phrase>")`
  - `path`: 관측값이 endpoint path 템플릿과 세그먼트 일치(템플릿 변수는 와일드카드)하고,
    생성 테스트의 requestPath가 동적 치환 없는 리터럴이면 → `equalTo("<requestPath>")`.
    비교·어설션 값 모두 query string(`?` 이후)을 제거한다 — Spring 기본 error `path`는
    query string을 포함하지 않는다.
  - `timestamp`: 항상 `notNullValue()` 유지 (비결정)
- 관측값이 계약 기대와 불일치하면(커스텀 에러 핸들러 등) 해당 필드는 기존대로 `notNullValue()`.
- SUCCESS path·비엔벨로프 FAILURE 바디는 규칙 미적용(기존 로직 유지).

## REQ-B: 입력 유도 카운트 provenance

최상위 입력이 JSON 배열(크기 n)일 때, 응답의 정수 필드 값이 n과 같고 필드명이 카운트 의미
어휘(count, created, updated, deleted, total, size, processed, affected, accepted)에 속하면,
그 값은 입력에서 함수적으로 유도된 결정값이다.

- **Given** `sampleInput`이 크기 n의 배열이고 응답 정수 필드 F의 관측값==n이며 F가 카운트
  어휘와 **정확히 일치**(부분 문자열 아님 — `account`·`discountRate` 오탐 차단), **When**
  합성하면, **Then** `body("F", equalTo(n))`.
- PK류 필드명(id 등, 기존 `isPkColumnName`)은 어휘에 넣지 않는다(오탐 방지).
- 조건 미충족 시 기존 규칙 그대로.

## REQ-D: 예외 메시지 리터럴 provenance (옵트인 노출과 결합)

- **캡처부(도구 1)**: 정적 인덱싱 시 각 endpoint 핸들러 메서드 본문(+동일 클래스 1단계 호출
  메서드)의
  `ResponseStatusException` 생성자 reason 인자에서 문자열 리터럴을 추출해
  `Endpoint.errorMessageLiterals`(List<String>, 신규·후방호환 — 구 그래프에선 빈 목록)로
  `graph.json`에 기록한다. 순수 리터럴은 전체 문자열, 연결식(concat)은 길이 8자 이상의 리터럴
  조각들을 기록한다.
- **생성부(도구 2)**: outcome=FAILURE path의 `sampleResponse`에 `message` 필드가 있을 때,
  - 관측 message가 endpoint의 리터럴과 정확히 일치 → `body("message", equalTo("<literal>"))`
  - 정확 일치는 없으나 어떤 리터럴 조각(≥8자)을 포함 → `containsString("<조각>")` (포함되는
    조각 중 최장 1개 — 동률 규칙: 길이 우선)
  - 어느 것도 아니면 → 기존 `notNullValue()`
  - **요청 충실도 전제**: 생성이 탐색 요청을 변형한 path(404 read의 부재-id 센티널 치환)에서는
    런타임이 타는 arm이 탐색 관측과 다를 수 있으므로 message 승격을 건너뛴다(라이브 반례:
    stale-arm 404가 센티널 치환으로 not-found-arm을 타며 message가 갈림). arm 무관 계약인
    REQ-A(status/error/path)는 그대로 승격한다.
- **노출은 옵트인**: 도구는 SUT 설정을 바꾸지 않는다. message가 응답에 없으면 이 규칙은
  자연히 비활성(어설션 대상 필드 부재). 사용자는 기존 `--sut-env`(탐색)와 자기 실행 환경
  설정(예: `SERVER_ERROR_INCLUDE_MESSAGE=always`)으로 노출을 켠다. 문서(docs/04·00 옵션 표)에
  이 조합과 "테스트 환경≠운영 환경 드리프트" 주의를 명기한다.

## E2E / 수용 테스트

- **REQ-A/B (기본 데모 회귀)**: `./e2e/run-e2e.sh` 가 여전히 `✅ E2E PASS — tests=85 skipped=0
  failures=0 errors=0`. 재생성 산출물에서 (a) 실패 경로 테스트의 `status/error/path`가
  `equalTo`로 나타나고 (b) `OrdersBatchPostTest`의 `created`가 `equalTo(<n>)`으로 나타난다.
- **REQ-D (옵트인 라이브 실증)**: message 노출 env로 탐색·생성·실행한 별도 런에서 (a) 생성
  테스트에 `message` 구체 어설션이 나타나고 (b) 전 테스트 green, (c) **뮤테이션 재실증** —
  `BookingController` nights 상한 `>30→>31` 주입 시 nights=31 실패-경로 테스트가 message
  어설션으로 FAIL한다(기존에는 마스킹되어 통과했음).
- 단위: `AssertionProvenanceUpgraderTest`(REQ-A/B/D 생성부), `ErrorMessageLiteralExtractorTest`
  (REQ-D 캡처부), shared-model 구버전 graph.json 역직렬화 회귀(`errorMessageLiterals` 부재 →
  빈 목록 정규화) 테스트.

## 추적 매트릭스

| REQ | 우선순위 | 수용 테스트 | 상태 |
|---|---|---|---|
| REQ-A | Must | AssertionProvenanceUpgrader 단위 + run-e2e.sh 재생성 검사 | 🟢 (2026-09-01: e2e 85건 green, 실패 경로 45건 status/error/path 전부 equalTo 승격) |
| REQ-B | Should(연기) | — | 🔵 |
| REQ-D | Must | 빌더 추출 단위 + 제너레이터 단위 + 옵트인 라이브 런 + 뮤테이션 재실증 | 🟢 (2026-09-01: 옵트인 런 85건 green + message equalTo 30건 합성, nights `>30→>31` 뮤테이션이 message 어설션으로 검출 — 기존엔 마스킹) |

**REQ-B 연기 기록 (2026-09-01 라이브 반례).** 첫 e2e 적용에서 `OrdersBatchPostTest.s201_1`이
`created` 기대 1 / 실제 0으로 실패했다. 원인: count는 입력 배열 크기뿐 아니라 **DB 상태**(항목이
참조하는 `users` 행 존재)에도 의존하는데, 배치(collection-body) happy 시나리오에는 시드 INSERT가
합성되지 않아 탐색 환경(explorer가 user를 시드)과 생성 테스트 런타임(시드 없음)이 갈렸다. 즉
"관측값==배열 크기"는 인과 증명이 아니었다 — GPT 리뷰 조건 1이 옳았고 기각을 철회한다. REQ-B는
count의 인과 입력(참조 행)이 시드로 재현되는 시나리오로 한정할 수 있을 때 재개한다.

**후속 이슈(REQ-D 반례가 드러낸 기존 갭 — 404 read 시나리오 충실도).** 404 read-path 생성은
모든 404 arm을 부재-id 센티널(`ABSENT_NUMERIC_ID`)로 재현한다. 그래서 "존재하는 stale 행 +
`includeStale=false`" 같은 **상태 의존 404 arm**은 생성 테스트가 stale 행을 시드해 놓고도
부재 id를 호출해 실제로는 not-found arm을 타 왔다(기존 notNullValue가 은폐, message 승격이
표면화). 올바른 재현은 시드된 stale id로 호출하는 것 — read-path 404 arm별 요청 합성으로
추적한다.

**후속 이슈(REQ-B 반례가 드러낸 기존 갭).** 배치(collection-body) happy 시나리오가 탐색 때
SELECT한 참조 행(users)을 생성 테스트에 시드로 상속하지 않는다 → 생성된 happy 테스트가 실제로는
아무 행도 만들지 않으면서(created=0) 201만 검증해 왔다(기존 notNullValue가 은폐). REQ-037의
collection-body 확장으로 추적한다.

## 비범위 / 반론

- 이중 실행 실증(C안)·JSONAssert 스냅샷(E안)·oracle-indistinguishable 리포팅(F안)은 본 건
  비범위(후속 검토).
- REQ-B의 이름 어휘는 휴리스틱이다 — 관측값 일치 + 정수 + 어휘 3중 조건으로 오탐을 줄였지만,
  배열 크기와 우연히 같은 비결정 카운트 필드(예: 전역 누적 카운터가 우연히 n)는 이론상 오탐
  가능. 수용: 그런 필드는 병렬 격리 하에서도 결정적이지 않으므로 e2e에서 즉시 드러나며,
  어휘에서 빼는 것으로 조정한다.
- REQ-A의 `path` 어설션은 requestPath가 동적(포맷 치환)이면 포기한다 — 리터럴 합성만 지원.
  어설션 값은 query string을 제거한 경로만 쓴다(엔벨로프 path 필드는 URI path 성분).
- 게이트웨이 라우트 엔드포인트(Ant 와일드카드 `*`/`**` path)는 REQ-A path 승격의 비범위 —
  세그먼트 매치가 성립하지 않아 notNullValue 유지(후속 검토, Sonnet 리뷰 I3).
- 리뷰 트리아지(3-벤더, 2026-09-01): Gemini 조건 3건(데이터 흐름 명시·reason phrase 맵·Endpoint
  후방호환)과 GPT 조건 2·3·4(캐시 버전 범프·데이터 흐름·query/동률 규칙)는 본 개정에 반영.
  GPT 조건 1(REQ-B를 옵트인으로 재분류)은 기각 — 관측값 일치+정수 타입+어휘 3중 조건이 이미
  오탐 게이트이고, 잔여 오탐은 결정성이 없어 e2e에서 즉시 드러나는 안전망이 있다(위 반론 절).
