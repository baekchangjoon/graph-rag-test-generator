# Progress Log

본 디렉터리는 graph-rag-test-generator 구축 과정의 단계별 진행/검수 기록입니다.

각 문서는 한 task 또는 일관된 task 묶음의 산출물 + 의도/설계 부합 확인 + 발견사항을 담고 있습니다.

## Phase별 인덱스

| Phase | 주요 문서 |
|---|---|
| **설계 & 골격** | [00-documentation](00-documentation.md), [01-skeleton](01-skeleton.md) |
| **Phase 0 — 단일 path JPA + E2E** | [02-shared-model](02-shared-model.md), [03-testlib](03-testlib.md), [04-dashboard](04-dashboard.md), [05-socket-mock-server](05-socket-mock-server.md), [06-graph-rag-builder](06-graph-rag-builder.md), [07-test-generator](07-test-generator.md), [08-phase0-e2e](08-phase0-e2e.md) |
| **Phase 1 — multi-path + javac + MyBatis** | [09-phase1-multi-path](09-phase1-multi-path.md), [10-javac-verify](10-javac-verify.md), [11-phase1-summary](11-phase1-summary.md) |
| **Phase 2 — HTTP 캡처 + WireMock + 합성기 통합** | [12-phase2-http](12-phase2-http.md), [15-phase2-synth-integration](15-phase2-synth-integration.md) |
| **Phase 3-5 skeleton** | [13-phase3-4-5-skeleton](13-phase3-4-5-skeleton.md) |
| **Phase 6 — 5M 레거시 아키텍처** | [14-phase6-legacy-arch](14-phase6-legacy-arch.md) |
| **합성기 통합 + WebSocket E2E + javaagent + JaCoCo + Socket synth** | [16-phase3-5-completion](16-phase3-5-completion.md) |
| **잔여 항목 (archive HTTP, WS 자동 캡처, 실 Socket stream wrap, fuzzer, Neo4j store)** | [17-residuals](17-residuals.md) |
| **최종 잔여 항목 (testlib HTTP 모드, response field tracker, JaCoCo scorer, Socket-system installer, Phase 4/5 E2E)** | [17-final-coverage-e2e-completion](17-final-coverage-e2e-completion.md) |

## 시간순 인덱스

| # | 제목 | 핵심 산출 |
|---|---|---|
| 00 | Documentation Pass | 12개 설계 문서 + SCHEMAS + OPEN-DECISIONS |
| 01 | 프로젝트 골격 셋업 | Gradle 8.13 멀티모듈, 7 모듈, build SUCCESS |
| 02 | shared-model TDD | 28 클래스, 13 test 클래스 |
| 03 | testlib 골격 TDD | API + SPI + noop 어댑터 + ServiceLoader |
| 04 | test-state-dashboard | Spring Boot REST + 누수 감지 (24 test) |
| 05 | socket-mock-server | Netty TCP + admin REST (14 test) |
| 06 | graph-rag-builder Phase 0 | datasource-proxy SQL 캡처 + JSON archive |
| 07 | test-generator Phase 0 | RestAssured 합성기 (11 test) |
| 08 | Phase 0 E2E | 전 사이클 통합 검증 (1 E2E) |
| 09 | Phase 1 multi-path | PathExplorer SPI + 멀티 테스트 합성 |
| 10 | javac 검증 | 합성 코드의 javac 컴파일 자동 검증 |
| 11 | Phase 1 summary | Phase 1 메트릭 충족 검수 |
| 12 | Phase 2 HTTP | CapturedHttpCall + WireMock recorder + stub composer |
| 13 | Phase 3-5 skeleton | WS/Socket 모델 + ProtocolDecoder SPI |
| 14 | Phase 6 legacy arch | 5M 라인 이식 아키텍처 문서 |
| 15 | Phase 2 합성기 통합 | HTTP stub이 TestSynthesizer 출력에 자동 포함 + Phase 2 합성 E2E |
| 16 | Phase 3-5 완결 | JaCoCo + CLI + Socket synth + WebSocket E2E + javaagent |
| 17 | 잔여 일괄 완료 | Archive HTTP, STOMP 자동 캡처, 실 Socket wrap, fuzzer, Neo4j |
| 17b | 최종 잔여 일괄 완료 | testlib HTTP 모드, response field tracker, JaCoCo scorer, Socket-system installer, Phase 4/5 socket E2E |

## 최종 상태 (Phase별)

| Phase | E2E | 합성 통합 | 추가 |
|---|---|---|---|
| 0 — JPA single path | ✅ Phase0E2eTest | ✅ | — |
| 1 — multi-path + javac + MyBatis | ✅ Phase1MultiPathE2eTest | ✅ | + MyBatis Interceptor |
| 2 — HTTP + WireMock | ✅ Phase2HttpE2eTest, Phase2HttpSynthesisE2eTest | ✅ stubFor 자동 | + archive 영속 |
| 3 — WebSocket/STOMP | ✅ Phase3WebSocketE2eTest | ✅ TestSynthesizer WS 통합 | + StompCaptureInterceptor |
| 4 — Netty Socket | ✅ Phase4NettySocketE2eTest | ✅ socket helper 합성 + SocketMockComposer 검증 | + ProtocolDecoder SPI + NettyPricingClient |
| 5 — Raw Socket javaagent | ✅ Phase5RawSocketE2eTest | ✅ SocketByteRecorder 캡처 → composer | + SocketSystemInstaller (bootstrap inject) + RawSocketPricingClient |
| 6 — 5M 레거시 | 📄 docs/10-legacy-scaling.md | — | + Neo4j GraphStore |

## 누적 수치

- 모듈: 9개
- commit: 27개 (main)
- 테스트 클래스: 70+
- 테스트 케이스: 130+ (전부 GREEN, Neo4j 통합 3개는 환경 gated)
- 설계 문서: 10 (docs/)
- 진행 기록: 19 (progress/)

## 패턴 + 원칙 (전 phase 일관)

- **TDD**: 모든 신규 클래스 — 테스트 먼저 RED 확인 → 최소 구현 → GREEN
- **결정적 합성**: 같은 입력 → 같은 출력. javac 컴파일 검증으로 보강
- **어댑터 분리**: GraphStore, HttpMock, SocketMock, Dashboard, Auth, ProtocolDecoder 모두 SPI
- **단계별 E2E**: 매 phase 끝에 docker-compose 가정 환경에서 통합 동작 시연
- **외부 데이터 무사용**: 운영 트래픽 없이 도구 자체가 빌드/실행하여 사실 수집

## 잔여 작업 (실 인프라 의존)

각 항목은 외부 환경 접근 또는 운영 작업 비중이 큼:

- 5M 레거시 PoC 실행 → 실제 레거시 프로젝트 + Neo4j 클러스터 + 분산 워커
- 실 java.net.Socket auto-instrument의 운영 검증 → `-javaagent` startup attach + 외부 SUT 프로세스
  (SocketSystemInstaller API/bootstrap inject은 완료 — in-process 단위테스트에선 advice fire 제한)
- JaCoCo runtime의 운영 적용 → SUT JVM에 jacocoagent.jar attach → exec.dump 외부 분석
  (JacocoCoverageScorer API + LoggerRuntime in-process는 완료 — JUL bridge 제약은 운영에선 무관)
- ResponseFieldReadTracker 자동 추출 → Jackson Mixin 또는 ByteBuddy getter hook (Phase 7+)
- Neo4j 통합 테스트 실행 → Docker 환경 (`GRAPH_RAG_NEO4J_TEST=1`)
- OpenAPI 응답 합성 강화 → 외부 시스템 사양 입수
- Socket 프로토콜 디코더 등록 → 프로토콜 사양 입수
