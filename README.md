# graph-rag

Java/Spring 애플리케이션의 블랙박스 REST 테스트 자산(테스트 코드 + DB 픽스처 +
mock 데이터)을 **결정적으로 생성**하는 시스템.
요구사항: `docs/01-overview.md`, 아키텍처: `docs/02-architecture.md`.

## 모듈

| 모듈 | 역할 |
|---|---|
| `shared-model` | 그래프 사실 / 생성 계약 / 이벤트 DTO (JSON 직렬화) |
| `graph-rag-builder` | 도구 1: SUT 분석 → 사실 캡처 → JSON 그래프 (LLM 없음) |
| `test-generator` | 도구 2: 그래프 + 요청 → RestAssured 테스트 결정적 합성 (LLM 없음) |
| `testlib` | 생성 테스트가 의존하는 helper (TestScope, SPI 어댑터) |
| `test-state-dashboard` | 테스트 자원 추적 + TTL 누수 감지 |
| `socket-mock-server` | Netty TCP mock + admin REST |
| `samples/order-service` | Phase 0 SUT (Spring Boot 3 + JPA + Postgres) |
| `e2e` | Phase 0 E2E 사이클 |

## 요구 환경

- JDK 17 (`gradle.properties`의 `org.gradle.java.home` 또는 `JAVA_HOME`)
- Docker (Testcontainers + docker-compose)

## 전 사이클 실행

```bash
./e2e/run-e2e.sh
```

흐름: SUT jar 빌드 → 도구 1이 Testcontainers + JaCoCo 분석 환경에서 분기 탐색
(휴리스틱 + coverage-guided fuzzer, endpoint당 요청 예산 60) 후 graph.json +
exploration-report.json 생성 → 도구 2가 endpoint별 전 path 테스트 생성
(`e2e/request-*.json`) → docker-compose 기동 → 생성 테스트 전부 실행 → 정리.
성공 시 `✅ E2E PASS — tests=N failures=0`.

개별 실행:

```bash
# 전체 단위/통합 테스트
./gradlew check

# 도구 1 단독
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> --out <dir>"

# 도구 2 단독
./gradlew :test-generator:run --args="generate --request <req.json> --graph <dir> --out <dir>"
```

## 문서

- 설계 spec: `docs/superpowers/specs/2026-06-10-phase0-rebuild-design.md`
- 구현 계획: `docs/superpowers/plans/2026-06-10-phase0-rebuild.md`
- 기능 단위 의사결정: `docs/decisions/`
- 단계별 진행 기록: `progress/`

## 현재 상태 / 다음 단계

- **Phase 0 완료** (2026-06-10): 단일 JPA endpoint의 build → graph → generate →
  run → pass 사이클 통과 (메트릭 1/1)
- **Phase 1 완료** (2026-06-10): 분기 탐색(휴리스틱 + fuzzer + JaCoCo) + MyBatis.
  endpoint 2개에서 12 path 발견 → 12 테스트 합성 → 12/12 통과.
  still_missing 리포트 + `--manual-paths` 수동 보강 경로 포함
- 다음: Phase 2 — WireMock 통합 (외부 HTTP, OTEL baggage 격리,
  `docs/09-implementation-roadmap.md`)
