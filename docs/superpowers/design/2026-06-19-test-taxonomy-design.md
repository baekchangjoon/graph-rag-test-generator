# 테스트 분류 체계 정리 (test taxonomy) — 설계

작성일: 2026-06-19 · 브랜치: `chore/test-taxonomy`

## 배경 / 문제

현재 테스트의 **이름(클래스 접미사)·태그(`@Tag`)·CI 샤드/job 이름**이 서로 다른 기준을
가리켜, "무엇이 unit/integration/e2e인지, in-process/out-of-process인지, docker/host에서
도는지"를 이름만으로 알 수 없다. 구체적 증상:

1. **하나의 버킷에 5가지 접미사.** `@Tag("integration")`인 7개(= in-process JUnit, 풀 SUT
   부팅, Docker 필요, `sut.jar` 있을 때만 활성)가 `*E2eTest` / `*AcceptanceTest` /
   `*PocTest` / `*IntegrationTest` / 그냥 `*Test`로 제각각 명명돼 있다.
2. **"E2e" 단어가 두 의미.** 클래스 이름의 `E2e`는 *integration 레이어*(in-process)를,
   CI job `e2e`와 `e2e/run-*.sh`의 `e2e`는 *진짜 out-of-process e2e*를 가리킨다.
3. **이름의 "Integration"/"IT"가 레이어를 예측 못 함.** `JacocoIntegrationTest`는
   태그 있음(it 샤드), `GeneratorFlakyFixIntegrationTest`·`SocketMockIntegrationTest`는
   태그 없음(unit 샤드). `HeaderTemplateHttpIT`는 Maven `IT` 관례 접미사인데 태그 없는
   in-process WireMock 테스트.
4. **`unit` 샤드가 unit이 아님.** `check -PexcludeTags=integration` = "integration 태그가
   안 붙은 전부"라서, Docker 데몬을 요구하는 Testcontainers 테스트 10개가 섞여 돈다.
5. **"docker"가 세 의미.** (a) 호스트 테스트가 Testcontainers로 docker를 *사용*,
   (b) 산출물이 docker 이미지*로* 패키징됨, (c) 테스트 하네스 *자체가* docker 안에서 돎(DinD).
   CI job 이름 `docker-builder-e2e`/`docker-services-e2e`만으론 (a)와 구분 안 됨.

## 목표 / 비목표

**목표**

- 세 축(레이어 / 프로세스 / 런타임)을 **직교**하게 분리하고, 각 축을 *한* 메커니즘으로 표현.
- 이름 접미사·`@Tag`·CI 샤드/job 이름이 **서로 모순 없이** 같은 분류를 가리키게 한다.
- 어떤 테스트도 누락·중복 실행되지 않게 보장(태그 분할이 전 테스트를 정확히 덮음).

**비목표**

- 테스트 로직 변경, 새 테스트 추가, out-of-process 셸 e2e를 JUnit으로 포팅하는 것.
- 외부 관측 가능한 *제품* 행위 변경(이 작업은 순수 내부 리팩터 + CI 설정 변경).

## 분류 체계 (세 직교 축)

| 축 | 값 | 표현 메커니즘 |
|---|---|---|
| **레이어** | unit / integration / e2e | unit=`*Test`(태그 없음), integration=`*IntegrationTest`+`@Tag("integration")`, e2e=`e2e/run-*.sh`(JUnit 클래스 아님) |
| **프로세스** | in-process / out-of-process | unit·integration=in-process(JUnit JVM이 대상 API/main을 직접 호출), e2e=out-of-process(실제 jar 프로세스 + docker-compose) |
| **런타임(docker)** | host-only / docker-required | `@Tag("docker")` = Docker 데몬 필요하지만 풀-SUT integration은 아닌 컴포넌트 테스트 |

레이어 정의(이 레포 기준):

- **unit** — in-process, 외부 데몬 불필요(또는 in-process WireMock/EmbeddedKafka/Spring slice).
  접미사 `*Test`, 태그 없음.
- **integration** — in-process JUnit이지만 **실제 order-service SUT를 부팅**하고(Testcontainers
  DB + SUT 프로세스), `@EnabledIfSystemProperty(sut.jar)`로 게이팅. 접미사 `*IntegrationTest`,
  `@Tag("integration")`. (풀 SUT를 띄우므로 Docker는 당연히 필요 → 별도 `docker` 태그 불필요;
  `integration` 샤드가 이미 분리 실행.)
- **docker(컴포넌트)** — in-process지만 단일 컨테이너(Postgres/MySQL/Kafka)를 띄우는 narrow
  테스트. 풀 SUT는 안 띄움. 접미사 `*Test`(레이어는 unit), 추가로 `@Tag("docker")`.
- **e2e** — out-of-process. 실제 빌드 산출물 jar/이미지를 프로세스로 실행. JUnit 클래스로
  표현하지 않으며, **`E2e` 접미사는 JUnit 클래스에 금지**(셸 스크립트 `run-*-e2e.sh`에만 사용).

규칙(불변식):

- R1. `@Tag("integration")` ⟺ 클래스 접미사 `IntegrationTest` ⟺ 풀 SUT 부팅.
- R2. JUnit 클래스 접미사에 `E2e`/`Acceptance`/`Poc`/`IT` 사용 금지. (e2e는 셸 전용.)
- R3. `@Tag("docker")` = 컨테이너 필요 + 풀-SUT 아님. unit 샤드에서 제외, docker 샤드에서 실행.
- R4. 태그 없는 `*Test` = host-only in-process unit. Docker 데몬 없이 통과해야 함.

## 변경 매핑

### A. integration 7개 → `*IntegrationTest`로 통일 (+ FQN 참조 갱신)

| 현재 | 새 이름 | 비고 |
|---|---|---|
| `cli/BuilderE2eTest` | `cli/BuilderIntegrationTest` | ci.yml `--tests` 갱신 |
| `cli/BuilderCollectionE2eTest` | `cli/BuilderCollectionIntegrationTest` | ci.yml `--tests` 갱신 |
| `cli/BuilderEndpointSelectorTest` | `cli/BuilderEndpointSelectorIntegrationTest` | ci.yml `--tests` 갱신 |
| `cli/OtelKafkaBuildAcceptanceTest` | `cli/OtelKafkaBuildIntegrationTest` | ci.yml `--tests` 갱신 |
| `capture/OtelHttpCaptureAcceptanceTest` | `capture/OtelHttpCaptureIntegrationTest` | `capture.*` 패턴이라 ci.yml 패턴은 그대로 |
| `capture/OtelKafkaCorrelationPocTest` | `capture/OtelKafkaCorrelationIntegrationTest` | `capture.*` 패턴 그대로 |
| `coverage/JacocoIntegrationTest` | (변경 없음) | 이미 규칙 준수 |

### B. Docker 필요 unit 10개 → `@Tag("docker")` 부여 (이름 유지)

builder: `run/KafkaCaptureReceiverTest`, `schema/SchemaExtractorMySqlTest`,
`schema/SchemaExtractorPostgresTest` ·
samples: `AuthApiTest`, `OrderApiTest`, `OrderCountWsTest`, `OrderExpressApiTest`,
`OrderReadApiTest`, `OrderSearchApiTest` · testlib: `api/KafkaHelperTest`

### C. 오해 유발 이름 정정 (in-process·host-only인데 integration/IT 접미사)

| 현재 | 새 이름 | 정체 |
|---|---|---|
| `socketmock/SocketMockIntegrationTest` | `socketmock/SocketMockServerTest` | @SpringBootTest in-process, docker 불필요 |
| `generator/GeneratorFlakyFixIntegrationTest` | `generator/GeneratorFlakyFixTest` | in-process, docker 불필요 |
| `run/HeaderTemplateHttpIT` | `run/HeaderTemplateHttpTest` | in-process WireMock |

### D. `build.gradle.kts` — 멀티 태그 필터 파싱 수정 (선행 필수)

**현재 버그(리뷰 I1, 3사 합의 critical).** 루트 `build.gradle.kts`(L27~29)는
`includeTags(it)` / `excludeTags(it)`에 Gradle 프로퍼티 **원문 문자열**을 그대로 넘긴다.
JUnit5 태그식 문법엔 콤마 연산자가 없으므로 `excludeTags("integration,docker")`는
`integration,docker`라는 *단일 리터럴 태그*를 찾게 되어 **아무 것도 제외하지 못한다**.
즉 D-CI의 `-PexcludeTags=integration,docker`는 그대로는 동작하지 않고 docker 테스트가
unit 샤드에 그대로 남아 AC2/AC3가 깨진다.

수정: 콤마로 split 후 vararg로 전달.

```kotlin
fun String.toTags() = split(",").map(String::trim).filter(String::isNotEmpty).toTypedArray()
(providers.gradleProperty("includeTags").orNull)
    ?.takeIf { it.isNotBlank() }?.let { includeTags(*it.toTags()) }
(providers.gradleProperty("excludeTags").orNull)
    ?.takeIf { it.isNotBlank() }?.let { excludeTags(*it.toTags()) }
```

### E. CI (`ci.yml`)

1. **`unit` 샤드 docker 제외:** `check -PexcludeTags=integration` →
   `check -PexcludeTags=integration,docker` (D의 split 수정 전제).
2. **`docker` 샤드 신설:** matrix에 `{ name: docker, args: "test -PincludeTags=docker" }` 추가.
   (컴포넌트 컨테이너 테스트 전 모듈 실행. `check` 아닌 `test`라 lint 등 비-test 태스크
   중복 실행 회피 — 그건 unit 샤드가 담당.)
3. **integration 샤드 `--tests` FQN 갱신** (A 매핑대로).
4. **job 이름 의미화:** `docker-builder-e2e` → `dind-builder-e2e`(하네스가 컨테이너 안=DinD),
   `docker-services-e2e` → `service-image-boot-e2e`(산출물 이미지 부팅). `release.needs`의
   참조도 동시 갱신.

### F. 코드/문서 참조 갱신 (리네임 후 깨지는 링크)

리네임은 `git mv` 후 **in-repo 참조**까지 갱신해야 한다. 실제 확인된 라이브 참조:

- **Java (필수):** `cli/OtelKafkaBuildAcceptanceTest`의 `{@link BuilderE2eTest}`,
  `capture/LogParserCaptureTest`의 주석 `BuilderE2eTest:178`, `cli/BuilderE2eTest`의 주석
  `OtelKafkaBuildAcceptanceTest가 …` → 각각 새 이름으로.
- **현행 번호 문서(갱신):** `docs/23-input-generation-flow.md`,
  `docs/24-exploration-backends-and-input-oracle.md`, `docs/25-input-discovery-theory.md`
  의 `BuilderE2eTest` 언급 → `BuilderIntegrationTest`.
- **CI/빌드:** `.github/workflows/ci.yml`(D), `build.gradle.kts` 자체엔 클래스명 없음.

**갱신하지 않음(근거 있는 제외):** `docs/superpowers/{plans,specs,requirements}/*`,
`docs/archive/*`, `.sdd/*`는 *완료된 과거 작업의 날짜별 기록(스냅샷)*이다. 당시 클래스명을
현재 이름으로 치환하면 사료를 왜곡하므로 의도적으로 두며, 본 작업의 정리 범위에서 제외한다.

### G. 신규 문서

- 신규 `docs/05-testing.md`를 만들어 이 분류 체계 표·R1~R4 규칙·**샤드↔태그 매트릭스**를
  싣고, `docs/06-test-environment.md`(런타임 토폴로지)에서 링크한다. (06은 docker-compose
  실행 토폴로지 문서라 분류 규칙을 섞지 않는다.) `glossary.md`에 unit/integration/e2e/docker
  정의 1줄씩.

#### 샤드 ↔ 태그 ↔ 레이어 매트릭스 (05-testing.md에 실을 표)

| CI 샤드/job | gradle 필터 | 레이어 | 프로세스 | docker |
|---|---|---|---|---|
| `check (unit)` | `excludeTags=integration,docker` | unit | in-process | 불필요 |
| `check (docker)` | `includeTags=docker` | unit(컴포넌트) | in-process | 컨테이너 |
| `it-cli-*` / `it-rest` | `includeTags=integration` | integration | in-process | 풀 SUT |
| `e2e` | (셸 `run-e2e.sh`) | e2e | out-of-process | compose |
| `dind-builder-e2e` | (셸, DinD) | e2e | out-of-process | 하네스가 컨테이너 안 |
| `service-image-boot-e2e` | (셸) | e2e | out-of-process | 산출물 이미지 부팅 |

## E2E / 수용 기준 (이 작업의 정의된 검증)

이 작업은 **순수 내부 리팩터 + CI 설정 변경**으로 제품의 외부 관측 행위를 바꾸지 않는다
(새 요구사항명세 미작성 — 카브아웃 적용). 따라서 새 제품 E2E를 추가하지 않고, 기존
테스트 인프라 자체의 무결성을 다음으로 검증한다(최고 실현 가능 수준 = CI 전체 실행):

- **AC1 (누락·중복 0).** 리네임/재태깅 전후로 *실행되는 테스트 메서드 총개수가 동일*하고,
  어떤 테스트도 두 샤드에서 중복 실행되지 않는다. 검증: 전후 `--tests` 리스트 또는 JUnit XML
  테스트 카운트 비교.
- **AC2 (태그 분할이 전체를 덮음).** `unit ∪ docker ∪ integration` = 전 테스트, 교집합 = ∅.
  검증: `excludeTags=integration,docker`(unit) + `includeTags=docker`(docker) +
  `includeTags=integration`(it) 카운트 합 = 무필터 카운트.
- **AC3 (unit 샤드 host-only).** `check -PexcludeTags=integration,docker`가 **Docker 데몬
  없이** 통과. 검증: 로컬에서 docker 끈 채 unit 샤드 실행(또는 daemon 미가용 환경).
- **AC4 (이름↔규칙 일치).** R1~R4를 깨는 잔존 항목 0. 검증 스크립트:
  `grep`으로 (`@Tag("integration")` 클래스가 모두 `IntegrationTest` 접미사인지),
  (`*IntegrationTest`가 모두 태그 있는지), (JUnit에 `E2e`/`Acceptance`/`Poc`/`IT` 접미사 0).
  `@Tag("docker")`는 grep으로 풀-SUT 여부를 못 가리므로, **B의 10개 명시 allowlist와
  정확히 일치**하고 그 10개 중 `@Tag("integration")`을 가진 것이 0임을 확인(semantic은
  allowlist로 대체).
- **AC5 (CI 그린).** 변경된 `ci.yml`로 통과. 단 `dind-builder-e2e`/`service-image-boot-e2e`는
  `if: pull_request || tag v*` 조건이라 **PR·릴리스 태그 트리거에서만** 실행됨 → AC5는 그
  트리거 기준으로 평가(메인 push는 unit/docker/it-*/e2e만 검증).

## 리스크 / 미해결

- **branch protection required checks(외부 설정).** job/샤드 이름이 바뀌면(`docker-builder-e2e`
  →`dind-builder-e2e` 등, `check (docker)` 신설) GitHub의 required status check 설정이 깨진다.
  → **수동 후속 필수**: 머지 전후로 저장소 설정의 required checks 목록을 새 이름으로 갱신.
  PR 본문에 명시한다.
- **samples:order-service 테스트의 위치.** 이들은 SUT 자신의 테스트라 graph-rag 테스트와 성격이
  다르나, 본 작업에선 "docker 필요"라는 사실만 반영(`@Tag("docker")`)하고 모듈 이동은 비목표.
- **비-CI e2e 스크립트 7개**(`run-attach-e2e`, `run-attach-ext-http-e2e`, `run-attach-otel-e2e`,
  `run-auth-headers-e2e`, `run-dist-e2e`, `run-docker-e2e`, `run-legacy-tram-sleuth-e2e`)는
  수동 실행으로 남으며 명명 규칙(`run-*-e2e.sh`)을 이미 따른다 — 변경 없음. (e2e/ 총 10개 중
  CI 연결 3개를 제외한 나머지.)
