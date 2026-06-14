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

# 도구 1 단독 (기본)
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> --out <dir>"

# 도구 1 — DB 타입을 SUT compose에서 자동 탐지 (Phase 7)
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --sut-compose <path/to/docker-compose.yml> --out <dir>"

# 도구 1 — JWT 인증 주입 (Phase 7)
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass secret \
  --out <dir>"

# 도구 1 증분 빌드 (Phase 6.2 — 클린 파티션은 이전 그래프에서 이월)
git diff --name-only main > changed.txt
./gradlew :graph-rag-builder:run --args="build ... --incremental-base <prev-graph-dir> --changed-files changed.txt"

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
  still_missing 리포트 + `--manual-paths` 수동 보강 경로 포함
- **Phase 2 완료** (2026-06-10): WireMock 통합. 외부 HTTP 캡처(임베디드 WireMock +
  `--external-stubs`/`--sut-env`), OTEL javaagent baggage 전파 실측, 생성 테스트의
  스텁 합성(baggage 격리 + consumedFields 투영), 병렬 안전 보고.
  14 path → 14 테스트 → 14/14 통과 (EXPRESS 201/재고부족 409 포함)
- **Phase 3 완료** (2026-06-10): WebSocket/STOMP. WsEndpoint 인덱싱, 자체 최소
  STOMP 클라이언트로 메시지 교환 캡처, testlib StompHelper, echo 마커 기반
  병렬 격리 합성. 16 테스트(HTTP 14 + STOMP 2) 16/16 통과
- **Phase 4·5 홀딩** (2026-06-11 사용자 결정): Netty/Raw Socket은 보류하고
  Phase 6을 선행
- **Phase 6.1·6.2 완료** (2026-06-11): 파티션 샤드 그래프 스토어
  (`PartitionedGraphStore`, Neo4j 보류 — `docs/decisions/graph-store-phase6.md`) +
  증분 빌드 (`--incremental-base`/`--changed-files`, 더티 파티션만 재탐색)
- **Phase 7 완료** (2026-06-14): 다중 HTTP method + JWT 인증 + DB 비종속 + GET read-path.
  GET/PUT/DELETE/PATCH 인덱싱, `--sut-compose` 기반 DB 타입 자동 탐지,
  `--auth-*` JWT 인증 주입(탐색·생성 테스트 양쪽), GET 조회 경로 시드+결정적 합성.
  22 테스트 전부 GREEN (auth POST 10, search 4, WS 2, GET-by-id 3, GET-by-userId 3)
- 다음: Phase 6.3 야간 풀 + PR 증분 운영, 6.4 raw socket 보강 어노테이션,
  Phase 7 stage 2(외부 spring-petclinic 적용)
  (`docs/09-implementation-roadmap.md`)
