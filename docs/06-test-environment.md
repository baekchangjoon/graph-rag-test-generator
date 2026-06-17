# 06 — 테스트 환경 (RestAssured + docker-compose)

생성된 테스트가 실행되는 환경. 도구 1의 분석 환경과는 별개다.

> **Prebuilt(소스 빌드 불필요)**: 보조 서비스는 GHCR 이미지로 발행된다 —
> `ghcr.io/baekchangjoon/test-state-dashboard`, `ghcr.io/baekchangjoon/socket-mock-server`(둘 다
> `:<version>`,`:latest`). SUT에 붙이는 OTEL agent(`otel-javaagent.jar`)는 [Releases](https://github.com/baekchangjoon/graph-rag-test-generator/releases) 자산이다.
> 아래 compose에서 `${SUT_IMAGE}`는 **테스트할 자기 앱의 컨테이너 이미지**, `${TEST_RUNNER_IMAGE}`는
> 생성된 테스트를 실행할 러너 이미지(testlib + RestAssured/JUnit 포함)다. 데모(`e2e/docker-compose.yml`)는
> 같은 구성을 `build:`로 만든 동작 예시다.

## 전체 구성

```yaml
# docker-compose.yml (테스트 실행 시)
services:
  app:                              # SUT (실 운영 빌드)
    image: ${SUT_IMAGE}
    environment:
      JAVA_TOOL_OPTIONS: "-javaagent:/agents/otel-javaagent.jar"
      OTEL_TRACES_EXPORTER: none
      OTEL_METRICS_EXPORTER: none
      OTEL_LOGS_EXPORTER: none
      OTEL_PROPAGATORS: tracecontext,baggage
      OTEL_SERVICE_NAME: ${SERVICE_NAME}
      OTEL_INSTRUMENTATION_HTTP_CLIENT_ENABLED: true
      # SUT의 외부 의존 URL은 mock 컨테이너로 redirect
      EXTERNAL_INVENTORY_URL: http://wiremock:8080
      EXTERNAL_SOCKET_HOST: socket-mock
      EXTERNAL_SOCKET_PORT: 9000
      DB_HOST: postgres
    volumes:
      - ./agents:/agents:ro
    depends_on: [postgres, wiremock, socket-mock]

  postgres:                         # 운영 동일 DBMS/버전
    image: postgres:15
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
    volumes:
      - ./schema:/docker-entrypoint-initdb.d:ro

  wiremock:                         # 외부 HTTP mock 서비스
    image: wiremock/wiremock:latest
    ports: ["9091:8080"]
    command: ["--global-response-templating"]

  socket-mock:                      # 자체 Netty 기반 mock (prebuilt GHCR 이미지)
    image: ghcr.io/baekchangjoon/socket-mock-server:latest
    ports: ["9000:9000", "9099:9099"]    # 9099 = admin

  test-state-dashboard:             # 모니터링 (옵션, prebuilt GHCR 이미지)
    image: ghcr.io/baekchangjoon/test-state-dashboard:latest
    ports: ["8099:8080"]
    environment:
      DASHBOARD_TTL_SECONDS: 300

  test-runner:                      # 생성된 테스트 실행
    image: ${TEST_RUNNER_IMAGE}
    environment:
      APP_BASE_URI: http://app:8080
      JDBC_URL: jdbc:postgresql://postgres:5432/app
      JDBC_USER: app
      JDBC_PASS: app
      HTTP_MOCK_ADMIN: http://wiremock:8080/__admin
      SOCKET_MOCK_ADMIN: http://socket-mock:9099
      DASHBOARD_URL: http://test-state-dashboard:8080
      AUTH_BASE_URI: http://app:8080/auth
      TEST_AUTH_MODE: real
    depends_on: [app, wiremock, socket-mock, test-state-dashboard]
```

## SUT의 OTEL javaagent 부착

핵심 결정: **SUT 소스 무수정**. OpenTelemetry javaagent를 docker-compose 단에서 부착.

- Java 8, Java 21 모두 지원
- Spring Boot 2, 3 모두 지원
- `OTEL_TRACES_EXPORTER=none`으로 트레이스 저장 없음 (propagation만 사용)
- 라이브러리 의존성 추가 없음

baggage propagator 활성 시 inbound 헤더 `baggage: test-id=...` 가 모든 outbound HTTP 호출에 자동 복사된다. 이게 WireMock 격리의 기반.

## SQL 캡처 모드 (`--sql-capture`)

빌더(도구 1)가 요청별로 어떤 SQL·바인딩이 실행됐는지 수집하는 방식이다. `--sql-capture <mode>` 로 고른다.

- **`otel` (기본)** — SUT의 OTEL agent가 내보내는 DB span에서 SQL과 바인딩 값을 받는다. 빌더가 요청마다
  고유 `traceparent` 를 발급해(HTTP 헤더 / Kafka 레코드 헤더) 그 trace의 DB span만 묶으므로, 동시·비동기
  요청도 서로 섞이지 않고 정확히 귀속된다. 빌더는 분석 동안만 OTLP 리시버를 띄워 SUT의 span을 받는다.
- **`log`** — SUT stdout의 Hibernate/MyBatis 로그를 파싱하는 폴백 경로. byte-offset 구간으로 귀속하므로
  단일 직렬 실행을 전제한다. agent의 DB span에서 SQL을 못 받는 환경에서 명시적으로 쓴다.

`otel` 모드에서도 어떤 요청의 trace에 DB span이 비면 그 요청만 자동으로 로그 파싱으로 폴백한다.

SQL 텍스트 속성은 OTEL agent 버전·설정에 따라 `db.query.text`(신규) 또는 `db.statement`(구) 중 하나로
오므로 빌더는 둘 다 읽는다. 바인딩 값은 `db.query.parameter.<index>`(0-based)로 받는다.

## RestAssured 테스트 스타일

```java
class OrdersPostTest {
    @BeforeAll
    static void config() {
        RestAssured.baseURI = env("APP_BASE_URI");
        // testlib가 환경변수에서 어댑터 자동 선택
    }

    @BeforeEach
    void setUp() {
        scope = TestScope.create();   // unique testId 발급
        // 픽스처, mock 등록
    }

    @AfterEach
    void cleanup() {
        scope.cleanup();              // 자기 스코프만
    }

    @Test
    void createOrder() {
        given(scope.rest.given())
            .header("Authorization", "Bearer " + scope.token)
            .header("baggage", "test-id=" + scope.testId)
            .contentType(ContentType.JSON)
            .body("{...}")
        .when()
            .post("/api/orders")
        .then()
            .statusCode(201)
            .body("status", equalTo("PENDING"));
    }
}
```

## DB

- 운영과 **동일 DBMS/메이저 버전** (예: postgres 15)
- 테스트는 JDBC 직접 접근 (testlib의 `JdbcHelper`)
- 시크릿/접속 정보는 env로 주입 (테스트 자체에 하드코딩 안 함)
- **DB 상태 검증은 하지 않음**. 응답만 검증.
- 정리는 자기 스코프만 (`WHERE <unique-key>=?`)

## 인증

두 가지 모드, 도구 2의 `auth_mode`로 선택:

### real

```java
token = scope.auth.login("test-user", "test-password");
// 또는 client_credentials, JWT 발급 등
```

`AuthClient`는 testlib 어댑터. 프로젝트별 인증 방식에 따라 어댑터 구현 교체.

### disabled

```yaml
# docker-compose
app:
  environment:
    SECURITY_DISABLED: true
```

테스트 코드에 토큰 발급 라인 생성 안 함. SUT에서 auth 비활성 환경변수 처리.

## 병렬 안전

- 각 테스트는 unique `testId` (UUID 또는 `namespaced`)
- DB 키, mock baggage, socket session 모두 testId 기반
- HTTP mock 격리: `baggage` 매칭
- Socket mock 격리: 프로토콜에 session field 있을 때만. 없으면 `@Execution(SAME_THREAD)`로 직렬

자세한 내용: `docs/07-mock-infrastructure.md`, `docs/08-dashboard.md`.

## 격리 가능성에 대한 도구 2의 처리

도구 2 출력의 `parallel_safety_report`로 사람/LLM 오케스트레이터에게 알림:

```yaml
parallel_safety_report:
  fully_parallel: [test_a, test_b]
  serial_required:
    - test: test_c
      reason: SOCKET_NO_SESSION
      details: "프로토콜에 session field 없음"
  scope_unreachable_tables:
    - test: test_d
      table: global_counter
      recommendation: "이 테스트는 isolated suite로 분리 권장"
  sut_propagation_missing:
    - test: test_e
      details: "SUT에 OTEL agent 미부착으로 추정"
      recommendation: "OTEL agent 부착 또는 직렬 실행"
```

## 환경 분리 원칙

| 항목 | 분석 환경 (도구 1) | 테스트 실행 환경 (생성된 테스트) |
|---|---|---|
| 목적 | 사실 캡처 | 테스트 검증 |
| DB | Testcontainers (운영 동일 DBMS) | docker-compose Postgres (운영 동일 DBMS) |
| HTTP mock | 임베디드 WireMock + recorder | docker-compose WireMock 서비스 |
| Socket mock | 임베디드 자체 Netty + recorder | docker-compose socket-mock 서비스 |
| SUT 실행 | 실 운영 JAR 외부 프로세스 (env 주입, HTTP 경계) | 실 운영 JAR + OTEL javaagent (컨테이너) |
| 테스트 도구 | InputOracle(static-literal + concolic ASM/Z3) + HeuristicExplorer/CoverageGuidedFuzzer + JaCoCo(arm-level) + 임베디드 WireMock + OTEL agent | RestAssured |

두 환경을 혼동하지 않는 것이 중요.
