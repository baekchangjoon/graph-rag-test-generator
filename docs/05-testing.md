# 05 — 테스트 분류 체계 (taxonomy)

이 레포 테스트의 **레이어 / 프로세스 / 런타임(docker)** 세 축을 직교하게 정의하고, 각 축을
*하나의* 메커니즘(이름 접미사 · `@Tag` · CI 샤드)으로 표현한다. 이름·태그·CI 샤드가 항상
같은 분류를 가리키게 하는 것이 목적이다. (배경/근거: `docs/superpowers/design/2026-06-19-test-taxonomy-design.md`)

## 세 축

| 축 | 값 | 표현 |
|---|---|---|
| **레이어** | unit / integration / e2e | unit=`*Test`(태그 없음), integration=`*IntegrationTest`+`@Tag("integration")`, e2e=`e2e/run-*.sh`(JUnit 아님) |
| **프로세스** | in-process / out-of-process | unit·integration=in-process(JUnit JVM이 대상 API/main 직접 호출), e2e=out-of-process(실제 jar 프로세스 + docker-compose) |
| **런타임** | host-only / docker-required | `@Tag("docker")` = Docker 데몬 필요하지만 풀-SUT integration은 아닌 컴포넌트 테스트 |

### 레이어 정의 (이 레포 기준)

- **unit** — in-process, 외부 데몬 불필요(또는 in-process WireMock/EmbeddedKafka/Spring slice).
  접미사 `*Test`, 태그 없음.
- **integration** — in-process JUnit이지만 **실제 order-service SUT를 부팅**(Testcontainers DB +
  SUT 프로세스)하고 `@EnabledIfSystemProperty(sut.jar)`로 게이팅. 접미사 `*IntegrationTest`,
  `@Tag("integration")`. (풀 SUT라 Docker는 당연히 필요 → `integration` 샤드가 이미 분리.)
- **docker(컴포넌트)** — in-process지만 단일 컨테이너(Postgres/MySQL/Kafka)를 띄우는 narrow
  테스트. 풀 SUT는 안 띄움. 접미사 `*Test`(레이어는 unit), 추가로 `@Tag("docker")`.
- **e2e** — out-of-process. 실제 빌드 산출물 jar/이미지를 프로세스로 실행. JUnit 클래스로
  표현하지 않으며, **`E2e` 접미사는 JUnit 클래스에 금지**(셸 `run-*-e2e.sh`에만 사용).

## 규칙 (불변식)

- **R1.** `@Tag("integration")` ⟺ 클래스 접미사 `IntegrationTest` ⟺ 풀 SUT 부팅.
- **R2.** JUnit 클래스 접미사에 `E2e`/`Acceptance`/`Poc`/`IT` 사용 금지. (e2e는 셸 전용.)
- **R3.** `@Tag("docker")` = 컨테이너 필요 + 풀-SUT 아님. unit 샤드에서 제외, docker 샤드에서 실행.
- **R4.** 태그 없는 `*Test` = host-only in-process unit. Docker 데몬 없이 통과해야 함.

## 샤드 ↔ 태그 ↔ 레이어 매트릭스

| CI 샤드/job | gradle 필터 | 레이어 | 프로세스 | docker |
|---|---|---|---|---|
| `check (unit)` | `excludeTags=integration,docker` | unit | in-process | 불필요 |
| `check (docker)` | `includeTags=docker` | unit(컴포넌트) | in-process | 컨테이너 |
| `it-cli-light` / `it-cli-otel` / `it-rest` | `includeTags=integration` | integration | in-process | 풀 SUT |
| `e2e` | 셸 `run-e2e.sh` | e2e | out-of-process | compose |
| `dind-builder-e2e` | 셸 `run-dind-builder-e2e.sh` | e2e | out-of-process | 하네스가 컨테이너 안(DinD) |
| `service-image-boot-e2e` | 셸 `run-service-image-boot-e2e.sh` | e2e | out-of-process | 산출물 이미지 부팅 |

> `dind-/service-image-boot-` job은 `pull_request` 또는 `v*` 태그에서만 실행된다(`if:` 조건).
> 멀티 태그 필터(`integration,docker`)는 루트 `build.gradle.kts`가 콤마로 split해 JUnit5에 넘긴다.

## "docker"의 세 의미 (job 이름이 구분)

- **(a) 호스트 테스트가 Testcontainers로 docker를 *사용*** — `check (docker)` 샤드(테스트 JVM은 호스트).
- **(b) 산출물이 docker 이미지*로* 패키징됨** — `service-image-boot-e2e`.
- **(c) 테스트 하네스 *자체가* docker 안에서 돎(DinD)** — `dind-builder-e2e`.

## 커버리지 (자체 테스트)

프로젝트 *자체* 테스트가 제품 코드를 얼마나 덮는지 측정한다. (도구가 측정하는 *SUT* 분기
커버리지인 `docs/coverage-progress.md`와는 별개다.)

- **집계 태스크:** 루트 `./gradlew jacocoAggregatedReport`. 제품 모듈 6개(`shared-model`,
  `testlib`, `test-state-dashboard`, `socket-mock-server`, `graph-rag-builder`,
  `test-generator`)의 `test`를 jacoco로 돌려 하나로 합친다.
  산출물: `build/reports/jacoco/jacocoAggregatedReport/`(XML + HTML).
- **CI 측정 범위 = unit + docker** (`-PexcludeTags=integration`). integration(풀-SUT 부팅)은
  단일 러너에서 직렬로 돌리면 무겁고(>12분) flaky해서 제외하고, e2e(out-of-process)는 별도
  프로세스라 애초에 잡히지 않는다. 즉 이 수치는 **in-process unit+컴포넌트 하한**이다(코멘트에
  범위 라벨 표기). 더 정확한 전체 수치는 `check` 샤드가 만든 `test.exec`를 아티팩트로 합치는
  방식으로 확장 가능(현재 범위 밖).
- **제외 모듈:** `samples`(fixture SUT)·`e2e`(하네스)는 집계 대상이 아니다.
- **CI:** `coverage` job(PR 전용, 정보성·non-blocking)이 집계를 실행해 (a) HTML 리포트를
  `coverage-html` 아티팩트로 업로드하고 (b) 전체 커버리지 표(+범위 라벨)를 PR 코멘트(마커 갱신)와
  job summary에 게시한다. 임계값 게이트나 `release.needs` 의존은 두지 않는다. 집계 XML이
  비면(exec 미병합) 그럴듯한 0%를 게시하지 않고 실패한다.
- 구현 메모: `jacoco-report-aggregation` 플러그인은 Spring Boot 모듈의 BOM 버전을 루트에서
  해석하려다 실패해, exec+classes+sources만 읽는 수동 `JacocoReport`를 쓴다. 코멘트 표는
  `.github/scripts/coverage_summary.py`가 집계 XML에서 생성한다.

## 현재 분류 현황

- **integration(7):** `BuilderIntegrationTest`, `BuilderCollectionIntegrationTest`,
  `BuilderEndpointSelectorIntegrationTest`, `OtelKafkaBuildIntegrationTest`,
  `OtelHttpCaptureIntegrationTest`, `OtelKafkaCorrelationIntegrationTest`, `JacocoIntegrationTest`.
- **docker(10):** builder `KafkaCaptureReceiverTest`·`SchemaExtractorMySqlTest`·
  `SchemaExtractorPostgresTest`, samples `AuthApiTest`·`OrderApiTest`·`OrderCountWsTest`·
  `OrderExpressApiTest`·`OrderReadApiTest`·`OrderSearchApiTest`, testlib `KafkaHelperTest`.
- **e2e(셸):** CI 연결 `run-e2e.sh`·`run-dind-builder-e2e.sh`·`run-service-image-boot-e2e.sh`,
  수동 8개(`run-attach-*`, `run-auth-headers-e2e`, `run-dist-e2e`, `run-docker-e2e`,
  `run-legacy-tram-sleuth-e2e`, `run-error-envelope-e2e` — 에러 엔벨로프 SUT 성공 오라클 검증).
- **나머지** = host-only unit (`*Test`, 태그 없음).
