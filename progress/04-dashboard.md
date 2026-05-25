# Progress: test-state-dashboard 골격 TDD 구현

**Date**: 2026-05-25
**Task**: #6 test-state-dashboard 골격 TDD 구현
**Result**: Spring Boot 3.5 앱 + 24 테스트 GREEN

## 산출물

### 모듈 구조 (`test-state-dashboard/src/main/java/io/graphrag/dashboard/`)

```
├── DashboardApplication.java            # @SpringBootApplication + @EnableScheduling
├── domain/
│   ├── TestRunStatus.java               # enum: ACTIVE | CLEANED | LEAKED | FAILED
│   ├── DbRow.java                       # record
│   ├── HttpStubInfo.java                # record
│   ├── SocketSessionInfo.java           # record
│   └── TestRunState.java                # record + with* mutators
├── store/
│   └── TestRunRegistry.java             # ConcurrentHashMap 기반 스토어 + 이벤트 처리
├── ingestion/
│   └── EventController.java             # POST /events, POST /events/batch
├── query/
│   └── DashboardQueryController.java    # GET /active, /leaked, /test/{id}, /tables/{n}/holders
├── leak/
│   ├── AlertChannel.java                # SPI
│   ├── ConsoleAlertChannel.java         # default 구현, 로그
│   ├── LeakDetector.java                # Clock 기반, 단위 테스트 가능
│   └── LeakDetectorScheduler.java       # @Scheduled wrapper
└── config/
    ├── JacksonConfig.java               # snake_case + java.time + tolerant
    └── DashboardConfig.java             # 빈 등록
```

`src/main/resources/application.yml` — 포트 8080, TTL 300s, scan 30s.

### 테스트 (`src/test/java/io/graphrag/dashboard/`)

- `domain/TestRunStateTest` — 7 tests (record mutator 동작)
- `store/TestRunRegistryTest` — 8 tests (이벤트 처리, list 필터, holders)
- `leak/LeakDetectorTest` — 4 tests (Clock fixed 기반 TTL 검증)
- `DashboardWebTest` — 5 tests (Spring Boot 컨텍스트 부팅 + MockMvc)

총 24 tests, BUILD SUCCESSFUL.

## TDD 사이클

| 단계 | RED | GREEN |
|---|---|---|
| TestRunState (record) | 7 assertion 컴파일 실패 | 7 통과 |
| TestRunRegistry | 8 assertion 컴파일 실패 | 8 통과 |
| LeakDetector + AlertChannel | 4 assertion 컴파일 실패 | 4 통과 |
| Web 통합 (Spring Boot) | 5 assertion 컴파일 실패 | 5 통과 (수정 후) |

## 발견 및 수정

1. **JUnit 버전 충돌 (test-state-dashboard)**
   - `spring-boot-starter-test`가 JUnit/AssertJ/Mockito 일체를 자체 BOM 버전으로 제공
   - 우리 `libs.bundles.testing.base`는 다른 JUnit 5.11 가져와 mismatch → "Implement generateDisplayNameForMethod"
   - 해결: Spring Boot 모듈은 `libs.bundles.testing.base` 사용 안 함, 오직 `spring-boot-starter-test`만.

2. **Logback 버전 충돌**
   - `runtimeOnly(libs.logback.classic)`이 Spring Boot BOM의 logback-core와 mismatch
   - 해결: 명시 logback 의존 제거, Spring Boot BOM에 위임.

3. **Jackson serialization 실패**
   - `TestRunState`가 final class with fluent accessors → Jackson이 BeanIntrospection 실패
   - 해결: record로 리팩터. 자동으로 accessor + serializer 지원.

## 설계와의 부합 확인

| 항목 | 결과 |
|---|---|
| docs/08-dashboard.md의 핵심 컴포넌트 | OK (event ingestion + state store + 누수 감지 + 알림) |
| SCHEMAS.md 4절 REST API (POST /events, GET /active 등) | OK |
| fire-and-forget — 발행 실패가 테스트 실패시키지 않음 | OK (server 측은 202 즉시 응답) |
| TTL 기반 누수 감지 + reaper opt-in | OK (Reaper는 후속 — 기본 비활성 알람만) |
| 무인증 + in-memory | OK |

## 의도적으로 후속으로 미룬 항목

- **Web UI (Thymeleaf 페이지)**: HTML 페이지로 활성/누수 표시. JSON REST가 동작하므로 UI는 nice-to-have. Phase 0 후 추가.
- **Reaper (자동 자원 정리)**: docs에 opt-in으로 정의됨. 기본 비활성이므로 초기 구현 생략.
- **EventLog (history)**: GET /history는 미구현. 누수 분석에 필요해지면 추가.
- **Slack/Webhook AlertChannel**: ConsoleAlertChannel만 구현. SPI 인터페이스로 확장 가능.
- **자원 일관성 검증 (DB 실제 스캔)**: 옵션. 디버깅 모드에서만 필요.

## 다음 단계

Task #7 — socket-mock-server.

- Spring Boot or pure Netty standalone
- 동적 포트 바인딩
- admin REST: POST /__admin/expectations
- byte 패턴 매칭 (prefix/exact/regex)
- stateful 세션 (다단계 프로토콜)
- 수신 byte hex dump 기록
