# Progress: testlib 골격 TDD 구현

**Date**: 2026-05-25
**Task**: #5 testlib 골격 TDD 구현
**Result**: API + noop 어댑터 + ServiceLoader 통합 완료

## 산출물

### testlib-api 모듈 (안정 인터페이스 + 핵심 구상 클래스)

```
testlib-api/src/main/java/io/graphrag/testlib/
├── api/
│   ├── HttpMockClient.java        # StubBuilder 포함
│   ├── SocketMockClient.java      # Session 포함
│   ├── AuthClient.java            # Token 포함
│   └── DashboardReporter.java
├── spi/                           # ServiceLoader용 SPI
│   ├── HttpMockAdapter.java
│   ├── SocketMockAdapter.java
│   ├── AuthAdapter.java
│   └── DashboardAdapter.java
└── scope/
    ├── Config.java                # 환경변수/Map 기반 설정
    └── TestScope.java             # builder + cleanup orchestration
```

### testlib-adapter-noop 모듈 (Phase 0 default)

```
testlib-adapter-noop/src/main/java/io/graphrag/testlib/noop/
├── NoopHttpMockClient.java
├── NoopSocketMockClient.java
├── NoopAuthClient.java
├── NoopDashboardReporter.java
├── NoopHttpMockAdapter.java       # SPI 구현
├── NoopSocketMockAdapter.java
├── NoopAuthAdapter.java
└── NoopDashboardAdapter.java

src/main/resources/META-INF/services/
├── io.graphrag.testlib.spi.DashboardAdapter   → NoopDashboardAdapter
├── io.graphrag.testlib.spi.HttpMockAdapter    → NoopHttpMockAdapter
├── io.graphrag.testlib.spi.SocketMockAdapter  → NoopSocketMockAdapter
└── io.graphrag.testlib.spi.AuthAdapter        → NoopAuthAdapter
```

## TDD 사이클

| 단계 | RED 확인 | GREEN 결과 |
|---|---|---|
| Config (env/Map → 설정 값) | 컴파일 실패 확인 | 7 assertion 통과 |
| TestScope (builder + 라이프사이클 + dashboard 이벤트) | 컴파일 실패 확인 | 8 assertion 통과 |
| Noop 어댑터들 (네 가지 + SPI 팩토리) | 컴파일 실패 확인 | 5 assertion 통과 |

## 주요 설계 결정

- **Builder 패턴 (TestScope)**: 어댑터를 명시적으로 주입받음. 테스트성 ↑.
- **`cleanup()` idempotent**: 중복 호출 시 추가 이벤트 발행 없음.
- **`AutoCloseable`**: try-with-resources 사용 가능.
- **dashboard.report는 fire-and-forget 위치**: noop 어댑터는 조용히 폐기. HTTP 어댑터는 짧은 timeout 후속 task.
- **ServiceLoader 발견**: 어댑터 SPI 인터페이스 + META-INF/services. 사용자가 classpath 어댑터만 바꾸면 됨.
- **Config는 record가 아닌 final class**: 환경변수 + 기본값 결합 로직 때문.

## 검증

```
$ ./gradlew build
BUILD SUCCESSFUL in 2s
41 actionable tasks: 19 executed, 22 up-to-date
```

- testlib-api: 15 test methods passing
- testlib-adapter-noop: 5 test methods passing
- shared-model: 45+ test methods passing (이전 task)
- 그 외 모듈은 source 없거나 placeholder만, build OK

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| SCHEMAS.md 3절 (testlib 인터페이스) 핵심 항목 매핑 | OK |
| 어댑터 SPI로 mock 백엔드 교체 가능 | OK (ServiceLoader 통합) |
| testlib는 SUT Java 버전 무관 (Java 17) | OK |
| Phase 0 환경에서 동작 (auth=disabled, no external HTTP/socket) | OK (noop 어댑터로) |
| testId 격리 기반 cleanup | OK (TestScope.cleanup → adapter.removeAllForScope/removeSession) |

## 의도적으로 후속 task로 미룬 항목

- **JdbcHelper**: 실제 DB 연결 필요. Phase 0 E2E 통합 task (#10)에서 본격 구현 + Testcontainers 통합 테스트.
- **RestAssuredHelper**: testlib 사용자가 직접 RestAssured.given() 호출 가능. helper는 baggage 자동 부착이 필요한 Phase 2 시점에 추가.
- **WireMock 어댑터, custom socket mock 어댑터**: Phase 2, 4 진입 시 신규 모듈로 추가.
- **자원 카운팅 (SCOPE_CLEANED의 정확한 release counts)**: 현재 boolean 플래그 기반. resource registry 도입은 Phase 1.
- **dashboard HTTP 어댑터 (fire-and-forget POST)**: test-state-dashboard task (#6) 후 적용.

## 다음 단계

Task #6 — test-state-dashboard 골격 TDD.

- Spring Boot 앱
- POST /events 수신
- 메모리 상태 저장 (ACTIVE/CLEANED/LEAKED tracking)
- TTL 기반 누수 감지
- 간단한 web UI (Thymeleaf)
- 핵심 query: GET /active, GET /leaked, GET /test/{id}
