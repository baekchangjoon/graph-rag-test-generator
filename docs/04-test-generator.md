# 04 — 도구 2: Test Generator

Graph RAG의 사실과 사용자 사양을 입력으로, **결정적**으로 테스트 자산을 합성한다.

LLM은 도구 안에 없다. 입력이 같으면 출력이 같다.

## 책임 경계

```
입력:
  - GenerationRequest (SCHEMAS.md 참조)
  - Graph RAG 사실 (도구 1 API 조회)
  - 기존 산출물 (이전 iteration 결과)

처리:
  1. 입력 파싱 + 기존 산출물 분석
  2. Graph RAG 조회 (필요한 사실들)
  3. 규칙 적용 → 사양 결정
  4. 큰 골격: 템플릿. 가변 길이 슬롯: 프로그램.
  5. (미구현/계획) self-check: compile + run + JaCoCo

출력:
  - GenerationResult (테스트 코드/데이터 + 리포트)
```

## 합성 방식: C (하이브리드, 템플릿-우위)

이전 답변에서 검토한 세 방식 중 C를 선택. RestAssured 스타일로 통일되면서 변형이 줄어 사실상 A에 가까운 C.

```
큰 골격 (변형 적음)        → 템플릿 (Mustache)
  - 테스트 클래스 구조
  - @BeforeAll, @BeforeEach
  - RestAssured given/when/then 체인

가변 길이 리스트로 채워지는 슬롯 → 프로그램 (Composer)
  - 픽스처 INSERT 시퀀스 (FK 순서 + N개 행)
  - WireMock 스텁 등록 라인 (N개)
  - Socket mock expectation 라인 (N개)
  - Assertion 라인 (응답 JSON 경로 + 상태)
```

## 출력 형태 (RestAssured)

```java
class OrdersPostTest {

    @BeforeAll
    static void config() {
        RestAssured.baseURI = env("APP_BASE_URI");
        httpMock   = HttpMockClient.from(env("HTTP_MOCK_ADMIN"));
        socketMock = SocketMockClient.from(env("SOCKET_MOCK_ADMIN"));
        jdbc       = Jdbc.from(env("JDBC_URL"), env("JDBC_USER"), env("JDBC_PASS"));
        auth       = AuthClient.from(env("AUTH_BASE_URI"));   // mode=real일 때만 활성
    }

    @BeforeEach
    void setUp() throws Exception {
        scope = TestScope.create();
        userId = scope.testId + "-user";

        // 사전 데이터 (apiParam 유래는 unique로 치환, literal은 유지)
        jdbc.update("INSERT INTO users(id, name) VALUES (?, ?)", userId, "John");

        // HTTP mock (baggage 매칭으로 격리)
        httpMock.stub("/inventory/stock")
                .withQueryParam("type", equalTo("EXPRESS"))
                .withBaggage("test-id", scope.testId)
                .respondJson("{\"available\": 50}")
                .register();

        // Socket mock (프로토콜에 session field 있을 때만)
        socketMock.bind("inventory", 9000)
                  .withSessionField("session", scope.testId)
                  .onReceiveHex("01 02 03")
                  .respondHex("02 00")
                  .register();

        // 토큰 (auth_mode = real)
        token = auth.login("test-user", "test-password");
    }

    @AfterEach
    void cleanup() throws Exception {
        // 자기 스코프만 cleanup. FK 역순.
        jdbc.update("DELETE FROM orders WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
        scope.cleanup();
    }

    @Test
    void createOrder_express_userId() {
        given(scope.rest.given())
            .header("Authorization", "Bearer " + token)
            .header("baggage", "test-id=" + scope.testId)
            .contentType(ContentType.JSON)
            .body(String.format(
                "{\"amount\":100,\"type\":\"EXPRESS\",\"userId\":\"%s\"}",
                userId))
        .when()
            .post("/api/orders")
        .then()
            .statusCode(201)
            .body("status", equalTo("PENDING"));
    }
}
```

DB 상태 검증은 없다. 응답 검증만.

## 규칙 카탈로그

도구 2의 핵심 로직은 다음 규칙들이다. 별도 `rules/` 패키지는 없고, 로직은
`Generator.java`와 `compose/*`(FixtureComposer, HttpMockComposer)에 인라인으로 구현돼 있다.

### 치환 규칙 (origin 기반)

| 규칙 | 트리거 | 동작 |
|---|---|---|
| API_PARAM 치환 | `binding.origin == API_PARAM` | 변수 추출, testId 기반 unique 값 부여 (path constraint 충족 범위 안에서) |
| LITERAL 보존 | `binding.origin == LITERAL` | 그대로 유지 |
| COMPUTED 처리 | `binding.origin == COMPUTED` | 케이스별 판단. 안전한 default 또는 경고 |

### 픽스처 합성 규칙

| 규칙 | 동작 |
|---|---|
| FK 정렬 | 부모 → 자식 순으로 INSERT |
| UNIQUE 충돌 회피 | testId 기반 unique 키 사용 |
| NOT NULL 채움 | 스키마 default 또는 안전한 dummy |
| 타입 렌더링 (`seedValueLiteral`) | 컬럼 JDBC 타입별: INT→정수, BIGINT→`L`, BOOL→bool, NUMERIC/DECIMAL/DOUBLE→`new BigDecimal(..)`, DATE→`LocalDate.parse(..)`, TIMESTAMP→`LocalDateTime.parse(..)`, TIME→`LocalTime.parse(..)`, UUID→`UUID.fromString(..)`, 그 외→문자열. (따옴표 문자열을 numeric/date 컬럼에 넣으면 INSERT가 깨지므로) |
| by-id 리소스 시드 | 비-GET by-id(PUT/DELETE)도 대상 리소스를 fixture로 INSERT(+cleanup DELETE) → 빈 DB 재현. path마다 고유 PK |
| 컬렉션 바디 → JSON 배열 방출 | `sampleInput`이 JSON 배열(컬렉션 `@RequestBody`/Kafka/WS happy 합성)이면 `FixtureComposer.bodyFormatFor`/`Generator`가 `{}`가 아니라 **배열 리터럴 `[ ... ]`** 을 요청 바디로 방출한다(원소 1개). 변수/단언 치환은 배열 원소를 unwrap해 적용한다. `ObjectNode` 전제의 path-var 치환·필드 단언은 배열이면 skip. |
| Cleanup 합성 | FK 역순 DELETE, 자기 스코프(`WHERE <unique-key>=?`)만 |

### Assertion 합성 규칙 (`assertionsFromResponse`)

| 규칙 | 동작 |
|---|---|
| 결정적 필드 → `equalTo` | 응답 필드 X의 값이 같은 이름의 입력/시드 필드 값(`knownByField`)과 일치하거나 SQL LITERAL 바인딩이면 구체값 단언. 타입맞춤: 정수/불리언 `equalTo(n)`, 문자열 `equalTo("s")`, 실수 보수적 notNull |
| 서버 생성 필드 → `notNullValue` | 시퀀스 id·count·timestamp·UUID 등(입력/시드에 없음, 또는 `looksServerGenerated`) |
| 필드명 매칭 | `knownByField`는 필드명-keyed라 우연한 값 충돌(id=1 vs amount=1)로 인한 오탐 없음 |

요청 경로 치환은 mustache `{{{requestPath}}}`(unescaped)로 — 쿼리 `=`가 HTML escape(`&#61;`)되면
boolean 파라미터 바인딩이 깨진다.

### Mock 격리 규칙

| 규칙 | 동작 |
|---|---|
| HTTP: propagation 있음 | `withBaggage("test-id", scope.testId)` 추가 |
| HTTP: propagation 없음 | **경고** + 직렬 실행 마크 또는 인스턴스 분리 힌트 |
| Socket: session field 있음 | session field 매칭 |
| Socket: session field 없음 | **직렬 실행 마크** (`@Execution(SAME_THREAD)`) |

### 인증 규칙

| 규칙 | 동작 |
|---|---|
| `auth_mode == real` AND `endpoint.auth_required` | 토큰 발급 + Authorization 헤더 |
| `auth_mode == disabled` | 토큰 코드 생략. SUT 환경변수로 auth 비활성 가정 |
| `endpoint.auth_required == false` | 인증 코드 생략 |

### Coverage delta 계산 규칙 (미구현/계획)

coverage-reporter가 미구현이므로 아래 규칙은 현재 동작하지 않는다.

| 규칙 | 동작 |
|---|---|
| 새로 커버된 path | 기존 baseline에 없던 `covers_paths` |
| 미커버 분기 | 그래프의 `not_yet_exercised` 와 비교 |
| 경고: scope-unreachable 테이블 | cleanup 불가 → 경고 + recommendation |

## 디렉터리

```
test-generator/src/main/
├── java/io/graphrag/generator/
│   ├── Generator.java            Mustache 골격 실행 + 슬롯 합성 조립
│   ├── cli/                      CLI 진입점 (GeneratorCli)
│   ├── client/                   도구 1 산출물(graph.json) 조회
│   │   ├── GraphRagClient.java
│   │   └── FileGraphRagClient.java
│   └── compose/                  가변 길이 슬롯 합성 (프로그램)
│       ├── FixtureComposer.java
│       ├── HttpMockComposer.java
│       └── ComposedFixture.java
└── resources/templates/         큰 골격 (Mustache) — 2개뿐
    ├── test-class.mustache
    └── ws-test-class.mustache
```

self-check(compile/run/JaCoCo)·coverage-reporter는 **미구현(계획)**.

## 결정성 보장

- 동일 입력 → 동일 출력 (commit SHA 단위)
- 시간/Random 사용 금지 (필요 시 input에서 받은 시드 사용)
- 템플릿/스니펫 변경 시 명시적 버전 bump

## 자원 사용

- LLM 호출 없음 → 비용 변동성 낮음
- 큰 비용 항목: graph.json 조회(`FileGraphRagClient`) + 합성
- self-check는 미구현(계획). 현재는 합성만 수행.
