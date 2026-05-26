# Progress: 5개 후속작업 일괄 완료

**Date**: 2026-05-26
**Tasks**: #33, #34, #35, #36, #37

## 산출

### #33 JaCoCo Gradle plugin

- 루트 `build.gradle.kts`에 jacoco 자동 적용 (모든 `java` 모듈)
- `tasks.test.finalizedBy(jacocoTestReport)` — 테스트 실행 시 자동 리포트
- `codeCoverageReport` 집계 태스크 (전 모듈)
- shared-model 라인 커버리지 측정 결과: 98%

### #34 GeneratorApplication CLI

- `CliRunner.run(String[])` — 종료 코드 반환 (테스트 용이)
- `GeneratorApplication.main` → `CliRunner.run` + `System.exit`
- flags: `--spec <spec.json> --out <dir>`
- spec.json = `MultiPathSynthesisInput` JSON 형식
- 클래스명 자동 추출, `TestArtifactWriter`로 파일 작성
- 4 tests: 정상 생성, 누락 인자, 알 수 없는 flag, 존재하지 않는 파일

### #35 Socket composer → TestSynthesizer 통합

- `PathContext`에 `capturedSocketIO` 필드 추가 (호환 생성자 유지)
- `TestSynthesizer.synthesizeMulti`:
  - `hasSocket` 감지
  - `SOCKET_MOCK_ADMIN` static 필드 + 헬퍼 메소드 `registerSocketExpectation` 생성
  - 헬퍼는 `java.net.http.HttpClient`로 socket-mock-server `/__admin/expectations`에 POST
- 4 tests: 코드 삽입, 미사용시 미생성, 혼합 path, javac 컴파일

### #36 WebSocket/STOMP E2E

- demo-sut에 `spring-boot-starter-websocket` 추가
- `WebSocketConfig` — STOMP broker (`/topic`) + endpoint (`/ws`)
- `OrderNotificationController` — `@MessageMapping("/orders/notify")` + `@SendTo("/topic/orders")`
- `Phase3WebSocketE2eTest`:
  - SUT 부팅 (random port)
  - `WebSocketStompClient`로 `/ws` 연결, `/topic/orders` 구독
  - `/app/orders/notify`에 `OrderNotification` 전송
  - broadcast 수신 확인
  - 송수신 메시지를 `CapturedWsMessage`로 모델링 (capture 패턴 시연)
- 1 test, GREEN

### #37 javaagent + ByteBuddy 골격

- 신규 모듈 `socket-capture-agent` (gradle subproject)
- ByteBuddy 1.15.10 + byte-buddy-agent (dynamic install)
- `AgentMain` — `premain` + `agentmain` + `install(Instrumentation)`
  - idempotent 보장 (`installed` flag)
  - ByteBuddy `AgentBuilder`로 `SampleTarget#invoke` retransform
- `SampleAdvice` — `@Advice.OnMethodEnter`에서 `CaptureCounter` 증가
- 빌드 manifest:
  - `Premain-Class: io.graphrag.agent.AgentMain`
  - `Agent-Class: io.graphrag.agent.AgentMain`
  - `Can-Redefine-Classes`, `Can-Retransform-Classes`
- 3 tests: 호출 instrumentation, idempotent install, 정상 동작 보존

## 검증

`./gradlew build`: BUILD SUCCESSFUL. 102+ tests PASSED.

## 의미

- **JaCoCo**: 빌드시 coverage report 자동 생성 → 개발자가 본인 코드의 테스트 적합성 확인
- **CLI**: GeneratorApplication이 진짜 실행 가능한 도구가 됨. spec JSON 입력으로 .java 파일 출력.
- **Socket 통합**: 합성기가 SQL/HTTP/Socket 3종 모두 인식. 한 path가 모두 가져도 단일 클래스에 깔끔하게 들어감.
- **WebSocket**: SUT가 STOMP 핸들러를 가진 경우의 E2E 가능성 입증. 합성기 통합은 후속.
- **javaagent**: ByteBuddy 기반 instrumentation 골격 동작 확인. raw socket 캡처는 후속 phase.

## 미완 / 후속 (Phase 1 stretch 등)

- #15 JaCoCo runtime 통합 (SUT JVM에 동적 부착하여 분석 시 coverage 수집)
- #16 Coverage-guided fuzzer
- WebSocket STOMP 메시지 자동 캡처 (analysis time)
- javaagent의 실 InputStream/OutputStream wrap (raw socket capture 본격)

## Phase별 최종 상태

| Phase | E2E | 합성 통합 |
|---|---|---|
| 0 — JPA single path | ✅ | ✅ |
| 1 — multi-path + javac + MyBatis | ✅ | ✅ |
| 2 — HTTP + WireMock | ✅ | ✅ (HttpStubComposer in synth) |
| 3 — WebSocket/STOMP | ✅ (메시지 송수신) | 🔶 (모델만, capture 자동화 후속) |
| 4 — Socket mock composer | — | ✅ (synth에 통합) |
| 5 — javaagent | — | ✅ (골격 + ByteBuddy 시연) |
| 6 — 5M 레거시 | 📄 | — |
