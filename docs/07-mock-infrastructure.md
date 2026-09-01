# 07 — Mock 인프라 및 testlib

생성된 테스트가 외부 의존(HTTP, Socket, DB, Auth)을 다루기 위한 helper 라이브러리와 mock 서비스.

## 두 개의 외부 mock 서비스

### HTTP: WireMock

- 성숙, 표준
- docker-compose의 별도 컨테이너
- admin API로 stub 동적 등록
- 매칭 조건: URL, query, header, body 패턴
- 격리는 `baggage` 헤더 매칭으로

### Socket: 자체 Netty 기반 mock server

이 분야의 오픈소스 표준이 약함. 검토 결과:

| 후보 | HTTP | TCP 바이너리 | 비고 |
|---|---|---|---|
| MockServer | 우수 | 제한적 | HTTP는 좋지만 binary는 매칭 정도 |
| Hoverfly | 보통 | 제한적 | Service virtualization 지향 |
| Imposter | OpenAPI 친화 | 미흡 | API mocking 중심 |
| 자체 Netty mock | n/a | 우수 | 작업 필요 |

→ **자체 제작**. 작은 standalone 모듈로 구현.

```
socket-mock-server/                  # standalone Spring Boot or Netty app
├── tcp/                             configurable port binding
├── udp/                             optional
├── recorder/                        수신 byte 기록 (디버깅용)
├── admin-rest/                      시나리오 등록 REST API
│   POST /__admin/expectations
│     { listen_port, on_receive_hex, respond_with_hex,
│       session_id, step_order, ... }
└── docker/
```

기능 범위 (최소):
- 포트 바인딩 + listening
- byte 패턴 매칭 (prefix / exact / regex over hex)
- 응답 byte 시퀀스
- stateful 세션 (다단계 프로토콜)
- 수신 byte hex dump 기록

## testlib: 어댑터 뒤로 숨김

```
testlib/
├── api/                             # 테스트 코드가 직접 사용하는 안정 API
│   ├── TestScope
│   ├── HttpMockClient
│   ├── SocketMockClient
│   ├── JdbcHelper
│   ├── AuthClient
│   └── RestAssuredHelper
└── adapter/                         # 백엔드 어댑터
    ├── http-mock/
    │   ├── wiremock/                # default
    │   └── mockserver/              # 옵션
    ├── socket-mock/
    │   └── netty-custom/            # default
    ├── jdbc/
    │   └── plain/                   # default (그냥 JDBC)
    ├── auth/
    │   ├── form-login/
    │   ├── oauth2-pwd/
    │   ├── oauth2-cc/
    │   ├── jwt-direct-issue/
    │   └── custom/                  # 프로젝트별
    └── dashboard-reporter/
        ├── http/                    # default
        └── noop/                    # DASHBOARD_URL 미설정 시
```

### 어댑터 선택 메커니즘

- 환경변수 + `META-INF/services` (ServiceLoader)
- `HTTP_MOCK_ADAPTER=wiremock` 같은 명시 가능
- 기본값은 WireMock + Netty custom + plain JDBC + noop dashboard

### Adapter 교체 시 영향

- **생성된 테스트 코드는 무변경**: import는 `testlib.api.*`만
- **testlib 의존성/설정만 교체**: adapter 모듈 swap

## 핵심 인터페이스 (요약)

자세한 시그니처: `testlib/src/main/java/io/graphrag/testlib/api/` 의 각 helper 클래스가 근거다.

```java
TestScope.create() {
    testId = generateUnique();
    http = HttpMockAdapter.fromEnv().create(config);
    socket = SocketMockAdapter.fromEnv().create(config);
    jdbc = JdbcAdapter.fromEnv().create(config);
    auth = AuthAdapter.fromEnv().create(config);
    rest = new RestAssuredHelper(testId);
    dashboard = DashboardAdapter.fromEnv().create(config);

    dashboard.report(SCOPE_CREATED);
    return this;
}

cleanup() {
    http.removeAllForScope(testId);
    socket.removeSession(testId);
    // db는 테스트 코드에서 명시적 cleanup (FK 순서 알아야 함)
    dashboard.report(SCOPE_CLEANED);
}
```

## 격리 메커니즘 요약

| 자원 | 격리 키 | 메커니즘 |
|---|---|---|
| DB | testId 기반 unique key (e.g., `${testId}-user`) | 자기 스코프 WHERE 조건 |
| HTTP mock | baggage `test-id=${testId}` | WireMock stub matcher |
| Socket mock | 프로토콜 session field | session-aware matcher |
| Socket (session 없음) | n/a | 직렬 실행 (`@Execution(SAME_THREAD)`) |

## 빠른 실패 (Fail-fast)

testlib는 다음 상황에서 즉시 fail (테스트 시작 시):

- `APP_BASE_URI` 미설정
- `JDBC_URL` 등 필수 env 누락
- 어댑터 ServiceLoader 결과 0개

대시보드는 예외 — `DASHBOARD_URL` 없으면 noop 사용 (조용히).

## 컴파일/실행 환경

- testlib 전체: Java 17 통일
- 생성된 테스트는 test-runner 컨테이너에서 실행되며 SUT의 Java 버전과 무관 (HTTP/JDBC로만 통신)
- SUT 자체는 Java 8 / Java 21 어느 쪽이든 OK

## 컨벤션 정리

- 환경변수는 도구 전체에서 동일 명명 (근거: `testlib` 의 env 로더와 `e2e/run-e2e.sh` 의 export 목록)
- 어댑터는 SPI 인터페이스만 알면 swap 가능
- 테스트 코드는 어댑터를 직접 import하지 않음
