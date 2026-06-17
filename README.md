# graph-rag

Java/Spring 애플리케이션의 블랙박스 REST 테스트 자산(테스트 코드 + DB 픽스처 +
mock 데이터)을 **결정적으로 생성**하는 시스템.

도구 두 개로 동작한다. **graph-rag-builder**(도구 1)가 대상 앱을 외부 프로세스로 띄워
호출해 보며 코드의 사실(엔드포인트·분기·발행 SQL·외부 호출·DB 스키마)을 `graph.json`으로
캡처하고, **test-generator**(도구 2)가 그 `graph.json`으로 RestAssured 테스트를 합성한다.
두 도구 안에 LLM은 없다.

## 처음이신가요?

→ **[docs/00-시작하기](docs/00-getting-started.md)** 부터 본다. 데모를 한 번 돌려보고
(`./e2e/run-e2e.sh`) 자기 앱에 적용하는 순서다.

자기 앱에 쓰려면 소스 빌드 없이 prebuilt를 받는다 — **[Releases](https://github.com/baekchangjoon/graph-rag-test-generator/releases)의
zip** 또는 **GHCR 이미지**(`ghcr.io/baekchangjoon/{test-generator,graph-rag-builder}`).
`test-generator`는 JRE 17만, `graph-rag-builder`는 +Docker. 상세는 시작하기의 트랙 B.

전체 문서 지도는 [docs/README.md](docs/README.md), 용어는 [docs/glossary.md](docs/glossary.md).
요구사항은 [docs/01-overview](docs/01-overview.md), 아키텍처는
[docs/02-architecture](docs/02-architecture.md).

## 모듈

| 모듈 | 역할 |
|---|---|
| `shared-model` | 그래프 사실 / 생성 계약 / 이벤트 DTO (JSON 직렬화) |
| `graph-rag-builder` | 도구 1: SUT 분석 → 사실 캡처 → JSON 그래프 (LLM 없음) |
| `test-generator` | 도구 2: 그래프 + 요청 → RestAssured 테스트 결정적 합성 (LLM 없음) |
| `testlib` | 생성 테스트가 의존하는 helper (TestScope, SPI 어댑터) |
| `test-state-dashboard` | 테스트 자원 추적 + TTL 누수 감지 |
| `socket-mock-server` | Netty TCP mock + admin REST |
| `samples/order-service` | 샘플 SUT (Spring Boot + JPA + Postgres). orders/search/WS/promo + **Booking**(by-id PUT/DELETE·enum·날짜·다필드 가드 — Stage 0–3b 회귀 커버) |
| `e2e` | Phase 0 E2E 사이클 |

## 요구 환경

- JDK 17 (`gradle.properties`의 `org.gradle.java.home` 또는 `JAVA_HOME`)
- Docker (Testcontainers + docker-compose)

## 전 사이클 실행

```bash
./e2e/run-e2e.sh
```

흐름: SUT jar 빌드 → 도구 1이 SUT를 **외부 프로세스**로 띄운 Testcontainers + JaCoCo 분석
환경에서 분기 탐색 후 graph.json + exploration-report.json 생성 → 도구 2가 endpoint별 전 path
테스트 생성(`e2e/request-*.json`) → docker-compose 기동 → 생성 테스트 전부 실행 → 정리.
성공 시 `✅ E2E PASS — tests=N failures=0`.

입력 생성: happy 입력 + (generic 경계 변이 ⊕ **InputOracle** 후보)를 HTTP로 호출한다. 오라클은
교체 가능하며 현재 두 구현을 합집합으로 쓴다 — `StaticLiteralOracle`(Spoon, 소스 리터럴 비교·문자열
동치) + `ConcolicOracle`(**ASM 바이트코드 심볼릭 스캔 + Z3**, 소스에 없는 값 도출: `amount*3==21→7`,
`code.length()==5→"xxxxx"`). 커버리지는 요청 단위 JaCoCo exec data를 누적 병합한 **arm-level**이고,
path 식별은 probe 지문(arm-aware)이라 발견 입력이 distinct 테스트로 보존된다.

그 위에 단계별 입력 발견(Stage 0–3b)을 쌓았다: **Stage 0** 유효 happy 합성(enum 첫 상수·날짜 ISO·이메일),
**Stage 1/2** 메서드 내 `&&` 다필드 가드 추출 + joint/enum 변이(`tier==VIP && loyalty<500` 등),
**Stage 3** by-id(GET/PUT/DELETE /{id}) path-id+리소스 시드·boolean 파라미터·enum 컬럼 시드,
**Stage 3b** mutating by-id 요청별 시드 리셋 + 결정성 인지 구체 어설션(생성 by-id 테스트가 빈 DB 재현).
원리: `docs/23-input-generation-flow.md`, `docs/24`, 이론: `docs/25-input-discovery-theory.md`.

개별 실행:

```bash
# 전체 단위/통합 테스트
./gradlew check

# 도구 1 단독 (기본)
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> --out <dir>"

# 도구 1 — DB 타입을 SUT compose에서 자동 탐지 (Phase 7)
#   --db-service/--db-image 로 compose 자동탐지를 오버라이드 가능, --with-redis 로 Redis 부착
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --sut-compose <path/to/docker-compose.yml> --out <dir>"

# 도구 1 — JWT 인증 주입 (Phase 7) — 필요 시 --auth-token-field/--auth-header/--auth-scheme 추가
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --auth-login-path /api/auth/login --auth-user admin --auth-pass secret \
  --out <dir>"

# 도구 1 — SUT별 JDK 지정(heterogeneous MSA) / 외부 HTTP 스텁·env 주입
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --sut-java-home /path/to/jdkXX --external-stubs <dir> --sut-env KEY=VAL --out <dir>"

# 도구 1 — attach 모드: 사용자 docker-compose로 SUT를 띄워 분석 (docs/26)
#   빌더가 override compose를 생성해 로깅·에이전트·포트를 주입하고 up/down을 소유
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --sut-compose <path/to/docker-compose.yml> --out <dir> \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-service postgres"

# 도구 1 증분 빌드 (Phase 6.2 — 클린 파티션은 이전 그래프에서 이월)
git diff --name-only main > changed.txt
./gradlew :graph-rag-builder:run --args="build ... --incremental-base <prev-graph-dir> --changed-files changed.txt"

# 도구 1 — 특정 엔드포인트만 탐색 (--endpoint, 콤마로 여러 개)
#   스펙은 id(post-api-orders) 또는 "METHOD /path"; --incremental-base 동반 시 나머지는
#   base에서 이월, 없으면 선택 단위만 담은 부분 그래프(정적 엔드포인트 목록은 풀 유지)
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> --out <dir> \
  --endpoint 'POST /api/orders'"

# 도구 2 단독
./gradlew :test-generator:run --args="generate --request <req.json> --graph <dir> --out <dir>"
```

## 문서

- **전체 지도: [docs/README.md](docs/README.md)** · 시작하기 [docs/00](docs/00-getting-started.md) · 용어 [docs/glossary.md](docs/glossary.md)
- 아키텍처: `docs/02-architecture.md` · 빌더 `docs/03` · 제너레이터 `docs/04`
- attach 모드(사용자 compose로 분석) + 커스텀 요청 헤더: `docs/26-attach-mode.md`
- 입력 생성·탐색 원리: `docs/23-input-generation-flow.md`, `docs/24-exploration-backends-and-input-oracle.md`
- 정적 분석 한계 + concolic 적용 범위: `docs/22-static-discovery-limits.md`
- 기능 단위 의사결정: `docs/decisions/`
- 개발 내력(specs/plans/progress, 시점 스냅샷): `docs/archive/`

## 외부 SUT 회귀·커버리지 (개발용)

```bash
# 외부 SUT 1종 격리 실행 (petclinic | auth-user | diary)
.work/run-suites.sh petclinic
# 4개 SUT 재생성 + handler/app-aggregate 커버리지 보고
.work/reg-coverage.sh
```

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
- **입력 발견 Stage 0–3b 완료** (2026-06-15): 유효 happy 합성(enum/날짜/이메일) → 다필드 `&&`
  conjunction joint/enum 변이 → by-id(PUT/DELETE/{id}) 진입(path-id 시드·boolean·enum 컬럼) →
  mutating by-id 시드 리셋·구체 어설션. 외부 spring-petclinic 적용 실측(coveredAppBranches 33→113/253,
  by-id 생성 테스트 fresh DB 16/16). order-service에 **Booking** 추가로 CI 회귀화 → **e2e 53 테스트 GREEN**.
- 다음: **Stage 4**(상태 의존 가드 양 arm을 in-process concolic 시드 변종으로 — PoC 검증됨),
  Phase 6.3 야간 풀 + PR 증분 운영, 6.4 raw socket 보강. (`docs/09-implementation-roadmap.md`)
