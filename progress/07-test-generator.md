# Progress: test-generator Phase 0 TDD 구현

**Date**: 2026-05-25
**Task**: #9 test-generator Phase 0 TDD 구현
**Result**: 결정적 RestAssured 테스트 합성 + 11 tests GREEN

## 산출물

```
test-generator/src/main/java/io/graphrag/generator/
├── GeneratorApplication.java        # 플레이스홀더 main (Phase 1+에서 CLI)
├── compose/
│   ├── FixtureStatement.java        # SQL + params + 영향 테이블 record
│   └── FixtureComposer.java         # captured SQL → fixture/cleanup statement
└── core/
    ├── SynthesisInput.java          # endpoint + captured SQL + 패키지 (Phase 0 input)
    └── TestSynthesizer.java         # Java 소스 코드 생성 (LLM 없음)
```

## 결정적 합성 특성

- `TestSynthesizer.synthesize(input)` — 동일 input → 동일 output
- LLM 호출 0
- StringBuilder + 직접 코드 생성 (Phase 0 단순화; Phase 1+에서 Mustache/JavaPoet 도입 검토)

## 생성된 테스트 형태

```java
package com.example.tests;

import io.restassured.RestAssured;
... (standard imports)

class OrdersPostTest {
    static String JDBC_URL = System.getenv("JDBC_URL");
    static String JDBC_USER = System.getenv("JDBC_USER");
    static String JDBC_PASS = System.getenv("JDBC_PASS");
    String testId;

    @BeforeAll
    static void config() {
        RestAssured.baseURI = System.getenv("APP_BASE_URI");
    }

    @BeforeEach
    void setUp() throws Exception {
        testId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users(id, name) VALUES (?, ?)")) {
                ps.setObject(1, "u-1");
                ps.setObject(2, "John");
                ps.executeUpdate();
            }
            // ...추가 INSERT
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        // FK 역순 DELETE
    }

    @Test
    void invoke() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
        .when()
            .post("/api/orders")
        .then()
            .statusCode(201);
    }
}
```

## TDD 사이클

| 테스트 | RED → GREEN |
|---|---|
| `FixtureComposer.fromCapturedSqls` | INSERT만 통과 |
| `FixtureComposer.cleanupFor` | FK 역순 DELETE |
| `TestSynthesizer` 클래스명 | path 마지막 세그먼트 + 메소드 |
| 표준 imports 포함 | RestAssured, JUnit5, JDBC |
| testId 생성 | BeforeEach + UUID |
| 픽스처 INSERT 포함 | captured SQL → ps.setObject |
| cleanup DELETE 역순 | orders 먼저, users 나중 |
| RestAssured invocation | given/when/then + statusCode |
| 결정적 출력 | 동일 input → 동일 output |
| 다른 endpoint 다른 className | OrdersPost vs UsersGet |

## 클래스명 도출 규칙

`{Resource}{Method}Test`
- Resource: path의 마지막 non-variable 세그먼트, capitalize
- Method: HTTP method 첫 글자만 대문자

예:
- `POST /api/orders` → `OrdersPostTest`
- `GET /api/users` → `UsersGetTest`
- `POST /api/users/{id}/orders` → `OrdersPostTest` (path variable `{id}` 스킵)

## 발견 및 수정

1. **graph-rag-builder 직접 의존 제거**: Spring Boot 플러그인 BOM이 test-generator에는 적용 안 되어 transitive resolve 실패. shared-model만 의존.
2. **클래스명 도출 단순화**: 이전 ad-hoc 로직이 복잡 → "마지막 세그먼트 + 메소드 + Test" 규칙으로 정리.

## 의도적으로 후속 phase로 미룬 항목

- **GenerationRequest/Result 전체 스키마**: 현재는 SynthesisInput만. Phase 1+에서 full 스키마 + REST API.
- **graph-rag-builder API 클라이언트**: 현재는 객체 직접 입력. Phase 1+에서 도구 1 query API 호출.
- **HTTP mock composer**: WireMock 스텁 등록 코드. Phase 2.
- **Socket mock composer**: Socket byte 시퀀스. Phase 4.
- **Mustache/JavaPoet 도입**: 코드 생성 복잡도 증가 시. 현재는 StringBuilder로 충분.
- **자기 검증 (compile + run + coverage)**: Phase 1+.
- **coverage delta / parallel safety report**: Phase 1+.
- **baggage 헤더 자동 부착**: Phase 2.
- **인증 모드 분기**: Phase 1.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| LLM 사용 없음 | OK (StringBuilder + 규칙 기반) |
| 결정성 (동일 input → 동일 output) | OK (테스트 검증) |
| RestAssured 스타일 (외부 HTTP 클라이언트) | OK |
| docker-compose env 가정 (JDBC env vars, APP_BASE_URI) | OK |
| 자기 스코프 cleanup | OK (FK 역순 DELETE) |
| testId 발급 | OK (UUID 기반) |

## 다음 단계

Task #10 — Phase 0 E2E 통합.

- demo-sut 본격 구현 (Spring Boot 3 + JPA + POST /api/orders)
- docker-compose.yml (postgres + sut)
- graph-rag-builder의 capture를 실 SUT에 적용해 Endpoint + CapturedSql 추출
- test-generator의 합성 결과를 실제 컴파일/실행
- 통합 sequence: 캡처 → 합성 → 실행 → 통과 검증
