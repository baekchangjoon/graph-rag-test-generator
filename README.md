# graph-rag-test-generator

Java/Spring 레거시 코드베이스를 대상으로 **블랙박스 REST API 테스트의 테스트 데이터와 테스트 코드를 결정적으로 생성**하는 시스템.

> Repo: https://github.com/baekchangjoon/graph-rag-test-generator

```
[사람 또는 LLM (외부 오케스트레이터)]
   ├──→ [도구 1: Graph RAG Builder]   ← 코드 → 그래프 RAG (LLM 없음)
   │       조회 API ←──── 사실 회수
   └──→ [도구 2: Test Generator]      ← 결정적 합성 (LLM 없음)
                                       템플릿 + 규칙
       산출물: RestAssured 테스트 + 픽스처 SQL + WireMock 스텁 + Socket mock 바이트
```

LLM은 **도구의 외부**에 위치. 도구 내부에는 없습니다.

---

## ⚡ Quickstart

요구: **JDK 17** (Amazon Corretto 권장), git.

```bash
# 1. clone + build
git clone https://github.com/baekchangjoon/graph-rag-test-generator.git
cd graph-rag-test-generator
./gradlew build

# 2. 합성기 CLI 설치
./gradlew :test-generator:installDist
# 실행 파일: ./test-generator/build/install/test-generator/bin/test-generator

# 3. 첫 테스트 생성 (예시 spec 사용)
./test-generator/build/install/test-generator/bin/test-generator \
    --spec examples/spec/minimal.json \
    --out /tmp/generated

cat /tmp/generated/com/example/tests/orders/OrdersPostTest.java
```

출력 예시 (요약):
```java
package com.example.tests.orders;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static io.restassured.RestAssured.given;

class OrdersPostTest {
    static String JDBC_URL = System.getenv("JDBC_URL");
    static String JDBC_USER = System.getenv("JDBC_USER");
    static String JDBC_PASS = System.getenv("JDBC_PASS");

    @BeforeAll
    static void config() {
        RestAssured.baseURI = System.getenv("APP_BASE_URI");
    }

    @Test
    void path_happy_201() throws Exception {
        String testId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            given()
                .contentType(ContentType.JSON)
                .body("{\"amount\":100,\"type\":\"EXPRESS\",\"userId\":\"u-1\"}")
            .when()
                .post("/api/orders")
            .then()
                .statusCode(201);
        } finally {
        }
    }
}
```

---

## 📖 Usage

CLI는 두 가지 입력 모드를 지원합니다.

### 1. `--spec` 모드 (단독 spec JSON)

```bash
test-generator --spec <spec.json> --out <output-dir>
```

spec.json은 `MultiPathSynthesisInput`의 JSON 표현 ([SCHEMAS.md](SCHEMAS.md) 0/2절 참조).
[examples/spec/](examples/spec/) 에 두 가지 예시:
- [minimal.json](examples/spec/minimal.json) — 단일 path, 캡처 없음
- [multi-path-with-http.json](examples/spec/multi-path-with-http.json) — 3 paths (201/400/404) + SQL + HTTP 캡처

### 2. `--archive` 모드 (graph-rag-builder가 생성한 archive)

```bash
test-generator \
    --archive <archive-dir> \
    --endpoint "POST:/api/orders" \
    --package com.example.tests \
    --out <output-dir>
```

archive 디렉터리에 4 JSON 파일:
- `endpoints.json` — 모든 endpoint
- `paths.json` — 탐색된 path (`endpoint_id`로 연결)
- `captured_sql.json` — SQL 캡처
- `captured_http.json` — 외부 HTTP 캡처

[examples/archive/](examples/archive/) 에 샘플.

### 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 정상 생성 + 파일 작성 완료 |
| 2 | 인자 누락/오류 |
| 3 | spec 파일 또는 archive 디렉터리 없음 |
| 4 | spec 파싱 또는 archive 읽기 실패 |
| 5 | 출력 파일 쓰기 실패 |

---

## 🚶 Example walkthrough

`examples/spec/multi-path-with-http.json` 으로 3 path (성공/400/404) + 외부 HTTP 호출 캡처를 포함한 테스트 클래스 생성:

```bash
./test-generator/build/install/test-generator/bin/test-generator \
    --spec examples/spec/multi-path-with-http.json \
    --out /tmp/gen-multi

cat /tmp/gen-multi/com/example/tests/orders/WithInventoryPostTest.java
```

생성되는 코드 골격:

```java
package com.example.tests.orders;

import io.restassured.RestAssured;
...
import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class WithInventoryPostTest {

    @BeforeAll
    static void config() {
        RestAssured.baseURI = System.getenv("APP_BASE_URI");
        String wmAdmin = System.getenv("HTTP_MOCK_ADMIN");
        if (wmAdmin != null) {
            java.net.URI u = java.net.URI.create(wmAdmin);
            WireMock.configureFor(u.getHost(), u.getPort());
        }
    }

    @Test
    void path_happy_201() throws Exception {
        String testId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        WireMock.reset();
        stubFor(get(urlPathEqualTo("/inventory/stock"))
            .withQueryParam("type", equalTo("EXPRESS"))
            .willReturn(aResponse().withStatus(200).withBody("{\"available\":50}")));
        // fixture INSERT INTO users, orders
        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
            ...
        }
        try {
            given()
                .contentType(ContentType.JSON)
                .body("{\"amount\":10,\"type\":\"EXPRESS\",\"userId\":\"u-1\"}")
            .when()
                .post("/api/orders/with-inventory")
            .then()
                .statusCode(201);
        } finally {
            // FK 역순 DELETE cleanup
        }
    }

    @Test
    void path_bad_amount_400() throws Exception { /* 400 case */ }

    @Test
    void path_missing_user_404() throws Exception { /* 404 case */ }
}
```

생성된 코드의 javac 컴파일 검증은 자동:
```bash
./gradlew :test-generator:test --tests "*.JavaSourceCompilerTest"
```

---

## 🔧 Configuration

### 환경변수 (생성된 테스트 실행 시)

[examples/env/test-runtime.env](examples/env/test-runtime.env) 참조.

| 변수 | 의미 | 필수 |
|---|---|---|
| `APP_BASE_URI` | SUT의 HTTP base URL | ✅ |
| `JDBC_URL` / `JDBC_USER` / `JDBC_PASS` | DB 접속 (운영 DBMS와 동일 버전 권장) | ✅ |
| `HTTP_MOCK_ADMIN` | WireMock admin URL | HTTP capture 사용 시 |
| `SOCKET_MOCK_ADMIN` | socket-mock-server admin URL | socket capture 사용 시 |
| `DASHBOARD_URL` | test-state-dashboard | ❌ (없으면 noop) |
| `TEST_AUTH_MODE` | `disabled` 또는 `real` | ❌ (기본 disabled) |
| `AUTH_BASE_URI` | 인증 발급 endpoint | real 모드 시 |
| `OTEL_BAGGAGE_KEY` | propagation 키 (기본 `test-id`) | ❌ |
| `HTTP_MOCK_ADAPTER` 등 | 어댑터 선택 (`wiremock`, `mockserver`, ...) | ❌ |

적용 예:
```bash
export $(grep -v '^#' examples/env/test-runtime.env | xargs)
# 그 다음 생성된 테스트 실행
```

### Spec 파일 스키마

자세히는 [SCHEMAS.md](SCHEMAS.md) — 도메인 모델 + Generator API.

핵심 필드 ([examples/spec/multi-path-with-http.json](examples/spec/multi-path-with-http.json) 참조):

```jsonc
{
  "endpoint": { /* Endpoint metadata */ },
  "paths": [
    {
      "path": { /* ExploredPath: id, sample_input, exit_status, ... */ },
      "captured_sql": [ /* fixture INSERT + cleanup DELETE 자동 도출 */ ],
      "captured_http_calls": [ /* WireMock stubFor 코드 자동 생성 */ ],
      "captured_socket_io": [ /* socket-mock-server expectation 자동 등록 */ ]
    }
  ],
  "test_package": "com.example.tests"
}
```

### docker-compose

운영 환경 (생성된 테스트 실행)은 [docker-compose.yml](docker-compose.yml) 참조. Postgres + WireMock + socket-mock-server + test-state-dashboard.

---

## 🧪 Phase별 E2E 단독 실행

```bash
./gradlew :samples:demo-sut:test --tests "*.Phase0E2eTest"               # 단일 JPA path
./gradlew :samples:demo-sut:test --tests "*.Phase1MultiPathE2eTest"      # 3 paths
./gradlew :samples:demo-sut:test --tests "*.Phase2HttpE2eTest"           # 외부 HTTP 캡처
./gradlew :samples:demo-sut:test --tests "*.Phase2HttpSynthesisE2eTest"  # 캡처 → 합성 통합
./gradlew :samples:demo-sut:test --tests "*.Phase3WebSocketE2eTest"      # STOMP 송수신
```

```bash
# Neo4j 통합 (Docker 필요)
GRAPH_RAG_NEO4J_TEST=1 ./gradlew :graph-rag-builder:test --tests "*Neo4j*"
```

---

## 📦 Module map

```
.
├── shared-model/            # 도메인 record (Endpoint, ExploredPath, CapturedSql/Http/Socket/WsMessage)
├── testlib-api/             # 테스트 helper SPI + TestScope + Config
├── testlib-adapter-noop/    # Phase 0 default 어댑터 + ServiceLoader 통합
├── test-state-dashboard/    # Spring Boot 앱: 테스트 자원 추적 + 누수 감지
├── socket-mock-server/      # Netty TCP mock + admin REST
├── socket-capture-agent/    # ByteBuddy javaagent + RecordingStreams (Phase 5)
├── graph-rag-builder/       # 도구 1: 캡처 (JPA/MyBatis/HTTP/STOMP) + 영속 (File/Neo4j) + Query API
├── test-generator/          # 도구 2: 결정적 합성 + javac 검증 + CLI
├── scout-launcher/          # Stage 3: YAML config 기반 SUT 부팅 + HTTP scout + archive 생성
├── scout-step-translator/   # Stage 2: paths.json/endpoints.json → scout-launcher config.yml
├── path-discovery-static/   # Stage 1: AST 기반 Spring controller 스캐너 → paths.json + endpoints.json
└── samples/demo-sut/        # PoC 대상 Spring Boot 3 + JPA + STOMP SUT
```

---

## 📊 Phase별 상태

| Phase | E2E | 합성 통합 | 추가 |
|---|---|---|---|
| 0 — JPA single path | ✅ | ✅ | — |
| 1 — multi-path + javac + MyBatis | ✅ | ✅ | + MyBatis Interceptor |
| 2 — HTTP + WireMock | ✅ | ✅ stubFor 자동 | + archive 영속 |
| 3 — WebSocket/STOMP | ✅ (송수신) | 🔶 모델만 | + StompCaptureInterceptor |
| 4 — Netty Socket | — | ✅ socket helper 합성 | + ProtocolDecoder SPI |
| 5 — Raw Socket javaagent | — | — | + RecordingStreams + ByteBuddy agent |
| 6 — 5M 레거시 | 📄 docs | — | + Neo4j GraphStore |

`./gradlew build`: BUILD SUCCESSFUL — 100+ 테스트 GREEN.

---

## 📚 문서

| 경로 | 내용 |
|---|---|
| [SCHEMAS.md](SCHEMAS.md) | 도메인 + Generator API 스키마 정의 |
| [OPEN-DECISIONS.md](OPEN-DECISIONS.md) | 의사결정 기록 |
| [docker-compose.yml](docker-compose.yml) | 테스트 실행 환경 가이드 |
| [docs/](docs/) | 주제별 설계 문서 (10개) |
| [progress/README.md](progress/README.md) | 18개 진행 기록의 통합 인덱스 |
| [examples/README.md](examples/README.md) | 입력 예시 안내 (spec / archive / env) |

---

## 🛠️ 구현 원칙

- **TDD**: 모든 모듈 테스트 우선
- **결정적 동작**: 동일 입력 → 동일 출력 (javac로 검증)
- **어댑터 분리**: mock/auth/storage/explorer 모두 SPI
- **단계별 E2E**: 매 phase 끝에 통합 검증
- **외부 데이터 무사용**: 운영 트래픽 없이 도구 자체가 빌드/실행으로 사실 수집

---

## 라이선스

Apache 2.0. [LICENSE](LICENSE) 참조.
