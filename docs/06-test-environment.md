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

## trace 모드 (`--trace-mode`)

빌더(도구 1)가 요청별 SQL·바인딩을 어떤 trace/상관 백엔드로 수집하는지 고른다. `--trace-mode <otel|sleuth|none>`(기본 `otel`). 공통 목표는 **API↔SQL 매핑 그래프**다.

| 모드 | trace/baggage 전파 | SQL 추출 | 매핑 범위 | 상관 헤더 |
|---|---|---|---|---|
| `none` | baggage만 (trace export off) | 로그 byte-offset(직렬) | 동기·동일프로세스(모놀리식 baseline) | 없음 |
| `sleuth` | Sleuth/B3(+baggage) | 로그 trace-id 상관 | + 비동기 서비스간(B→C) | B3 |
| `otel` | OTEL agent(traceparent+baggage) | OTLP DB span(로그 fallback) | + 비동기 서비스간(B→C) | traceparent |

- `sleuth`(레거시 Java8+Sleuth+Eventuate/Tram): 요청별 B3 trace-id를 A에 주입하고 그 trace-id가 박힌 로그 라인만 상관해 A→B→C SQL을 회수한다. OTEL javaagent를 부착하지 않는다(레거시 `brave.Tracing` 빈 충돌 회피). **전제**: SUT logback이 `%X{traceId}`(또는 동등 MDC 키)를 출력해야 한다(SUT 제공자 책임).
- `none`: 추적 전무 SUT의 격하 baseline(직렬·격리 없음). 구 `--sql-capture log` 와 동등. OTEL javaagent는 부착되나 `OTEL_TRACES_EXPORTER=none`으로 trace export를 꺼 SQL 상관용 trace-id가 없다(baggage 전파는 유지 — WireMock 격리용). 자세히는 [docs/26](26-attach-mode.md).
- 멀티서비스 로그 수집: attach 모드에서 `--capture-services a,b,c` 로 여러 컨테이너 로그를 한 파일에 인터리브 tail한다(비동기 B→C 캡처). 미지정 시 `--app-service` 단일.

## Kafka outbound produce 캡처

`--trace-mode`(SQL 캡처 경로)와 **직교**하는 별도 백엔드다. SUT가 발행하는 outbound Kafka 메시지를 캡처해 어설션을 만든다.

- 활성: attach 모드 `--kafka-bootstrap <host:port>`, 분석 모드 `--with-kafka`. 미지정 시 produce 캡처를 건너뛴다.
- 동작: 백그라운드 `KafkaCaptureReceiver`가 브로커 토픽을 구독하고, 요청별 trace-id(otel=traceparent, sleuth=B3)로 레코드를 귀속해 `CapturedEventEmit`(topic+key+payload)을 만든다.
- 생성 테스트: `KafkaHelper.consumeNextRecord(topic[, expectedKey], timeout)` + `JSONAssert`로 단언한다. 비결정 필드는 제거하고, 토픽/키 필터로 복수 emit을 각각 검증한다.
- inbound `@KafkaListener` 소비(컨슈머 탐색)와는 다르다 — 이쪽은 SUT가 **내보내는** 메시지의 캡처다.
- 생성된 `KafkaHelper` 어설션을 실행하려면 테스트 실행 compose에 Kafka 브로커 서비스가 있어야 한다(예: `e2e/docker-compose.yml`의 `kafka` 서비스 참조).

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

두 가지 모드, 도구 2의 `authMode`(GenerationRequest 필드, JSON camelCase)로 선택:

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
