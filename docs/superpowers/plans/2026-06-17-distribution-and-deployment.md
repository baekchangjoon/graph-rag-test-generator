# 배포 계획 — prebuilt 산출물로 "직접 빌드" 제거 (옵션 A + D)

- 작성: 2026-06-17 · 개정: 2026-06-17 (3-모델 리뷰 triage 반영)
- 상태: 계획(plan only) — 구현 미착수.
- 범위: 사용자가 **도구 저장소를 소스에서 빌드하지 않고도** 두 CLI를 받아 실행하고, 생성된
  테스트를 **컴파일·실행**까지 하게 한다. 선택된 방식: **A) distZip + GitHub Release** +
  **D) Docker 이미지**. 생성 테스트를 실제로 돌리려면 CLI 외에 실행 자산(testlib·mock 서비스·
  OTEL agent)도 prebuilt로 제공해야 하므로 본 계획에 포함한다.

## 1. 배경과 문제

현재 사용자는 저장소를 clone하고 `./gradlew :graph-rag-builder:run ...`로 실행해야 한다 —
**JDK 17 + Gradle + 도구 소스 빌드 환경**이 필수다.

두 도구의 실행 요구사항은 다르다(`build.gradle.kts` 기준):

- **`test-generator`**: 의존성이 `shared-model` + mustache + slf4j뿐. `graph.json`만 있으면
  실행되고 **Docker도 DB도 불필요**.
- **`graph-rag-builder`**: testcontainers(postgresql/mysql/mariadb/kafka) + **Redis
  (`--with-redis`, `GenericContainer(redis:7-alpine)`)** + jacoco-agent + wiremock,
  그리고 SUT를 외부 JVM 프로세스로 기동. 따라서 **실행 시 Docker 데몬이 반드시 필요**하다 —
  도구의 동작 원리이지 패키징으로 없앨 항목이 아니다. 또한 입력으로 **SUT의 소스·boot jar·
  docker-compose**가 항상 필요하다(이건 "도구 소스 빌드"와 무관한, 분석 대상 입력이다).

배포로 **제거 가능**: "도구를 소스에서 빌드"(Gradle·도구 소스).
**제거 불가**: builder의 Docker 의존, builder 입력인 SUT 소스/jar/compose.

## 2. 전제 사실 (저장소 검증됨)

- 두 CLI 모듈 모두 Gradle `application` 플러그인 + `mainClass` →
  `distZip`/`distTar`/`installDist`/`assembleDist` 태스크가 이미 존재.
- builder는 OTEL javaagent를 `processResources`로 **자기 jar 안에**(`agents/otel-javaagent.jar`)
  번들 → builder 배포 산출물의 `lib/`에 포함되어, **분석 환경용** OTEL agent는 별도 조달 불필요.
  (주의: 생성 테스트의 **실행 환경**에서 SUT에 부착하는 OTEL agent는 별개 자산 — §5.4.)
- builder `--sut-compose`는 **필수**다(`BuilderCli`가 없으면
  `IllegalArgumentException("--sut-compose ... is required")`).
- builder는 SUT 프로세스 커버리지를 JaCoCo **TCP 서버(127.0.0.1:동적포트)**로 수집하고
  `CoverageClient`가 `localhost`로 접속한다(`JacocoAgent`/`BuilderCli`).
- 생성 테스트는 `io.graphrag.testlib.*`(TestScope, Jdbc, HttpMockClient, AuthClient,
  StompHelper 등)를 import한다(`docs/04`) → **testlib 없이는 컴파일 불가**.
- 생성 테스트의 실행 환경(`docs/06`)은 운영 DBMS + WireMock + `socket-mock-server` +
  `test-state-dashboard` 컨테이너를 전제(둘 다 standalone bootJar 모듈).
- CI(`ci.yml`): `ubuntu-latest` + temurin 17 + `setup-gradle`, `org.gradle.java.home`를
  러너 JDK로 덮어씀. e2e 잡은 Docker 가능한 Linux 러너에서 `run-e2e.sh` 실행.
- 프로젝트 버전 미설정(루트 `build.gradle.kts`에 `version` 없음).

## 3. 버전 정책

- 버전 출처 = git 태그. 형식 **`vX.Y.Z`** 또는 pre-release `vX.Y.Z-<id>`.
- 루트 `build.gradle.kts`의 **`allprojects` 블록**에 다음을 둔다(없으면 distZip 산출물명에 버전이
  안 붙는다 — version은 루트→서브프로젝트로 자동 전파되지 않고 각 서브프로젝트 version이 산출물명에 쓰임):
  `version = providers.environmentVariable("RELEASE_VERSION").orElse("0.0.0-SNAPSHOT").get()`.
- release 워크플로가 태그명을 `RELEASE_VERSION=${GITHUB_REF_NAME#v}`로 주입.
- 산출물명은 `application` 규칙에 따라 `<module>-<version>.zip`.

## 4. 옵션 A — distZip + GitHub Release

### 4.1 산출물과 실행 모델

`./gradlew :graph-rag-builder:distZip :test-generator:distZip` →
`graph-rag-builder-<v>.zip`, `test-generator-<v>.zip`(각 `bin/<launcher>` + `lib/*.jar`).

```bash
# builder — JRE 17 + Docker + (SUT 소스/jar/compose) 필요. --sut-compose는 필수.
./graph-rag-builder-<v>/bin/graph-rag-builder build \
  --sut-src <앱>/src/main/java --sut-jar <앱>/build/libs/app.jar \
  --sut-compose <앱>/docker-compose.yml --out ./out/graph

# generator — JRE 17만(Docker 불필요)
./test-generator-<v>/bin/test-generator generate \
  --request req.json --graph ./out/graph --out ./out/generated
```

`bin` 런처는 `JAVA_HOME` 또는 PATH의 `java`를 사용 → 사용자는 **JRE 17**만 있으면 된다.

### 4.2 릴리스 워크플로 (구현: `ci.yml`에 게이트된 `release` 잡)

별도 워크플로 대신 **기존 `ci.yml`에 통합**해 hard gate를 만든다(구현됨):

- `ci.yml` `on.push.tags`에 `['v*']` 추가 → 태그 push 시 check+e2e가 돈다.
- `release` 잡: `needs: [check, e2e]` + `if: startsWith(github.ref, 'refs/tags/v')` →
  **check·e2e 통과 + 태그일 때만** 실행(깨진 산출물 발행 방지).
- 태그를 `TAG` env로 받아(직접 `${{ }}` 보간 금지) SemVer 검증
  (`^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$`) 후, `RELEASE_VERSION=${TAG#v}`로
  `:graph-rag-builder:distZip :test-generator:distZip :testlib:jar` 빌드 →
  `gh release create "$TAG" --generate-notes`로 두 zip + testlib jar 첨부.
- 권한 `contents: write`. (§5.4의 이미지·OTEL 자산은 Phase D에서 같은 잡/워크플로에 추가.)

## 5. 옵션 D — Docker 이미지 + 실행 자산

### 5.1 generator 이미지 (쉬움)

- 베이스 `eclipse-temurin:17-jre`. **이미지 빌드 전 `./gradlew :test-generator:installDist` 선행**,
  Dockerfile은 `COPY test-generator/build/install/test-generator /app`,
  `ENTRYPOINT ["/app/bin/test-generator"]`. Docker 불필요(이미지 내부 실행만).
- (또는 Gradle wrapper 기반 멀티스테이지 빌드로 installDist를 이미지 빌드 안에서 수행.)
  ```bash
  docker run --rm -v "$PWD:/work" -w /work ghcr.io/<owner>/test-generator:<v> \
    generate --request req.json --graph ./out/graph --out ./out/generated
  ```

### 5.2 builder 이미지 (어려움 — Testcontainers/JaCoCo 네트워킹)

builder는 컨테이너 안에서 (a) Docker 데몬에 접속해 Testcontainers DB/Kafka/Redis를 띄우고
(b) SUT를 외부 JVM 프로세스로 기동하며 (c) 그 프로세스의 JaCoCo TCP 서버에서 커버리지를 읽는다.

- **Docker 접근**: host `/var/run/docker.sock` 마운트(docker-out-of-docker).
- **`--network host` 가 load-bearing(두 가지 이유, Linux 1차)**:
  ① SUT 프로세스가 Testcontainers DB(host 매핑 포트)를 localhost로 접근,
  ② JaCoCo TCP 서버가 `127.0.0.1:동적포트`에 바인드되고 `CoverageClient`가 `localhost`로 접속 —
  builder JVM과 그 SUT 자식 프로세스가 같은 loopback 네임스페이스에 있어야 한다. bridge 네트워크로
  바꾸면 커버리지 수집이 조용히 실패한다. macOS/Windows는 `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`
  경로의 best-effort(문서에 한계 명시).
- **`--with-redis`**: Redis 의존 SUT는 동일하게 socket 마운트 기반 Testcontainers Redis를 사용.
- 이미지: JRE 17 포함(런처 + SUT 프로세스). Dockerfile `ENTRYPOINT ["/app/bin/graph-rag-builder"]`,
  `COPY graph-rag-builder/build/install/graph-rag-builder /app`. `installDist` 선행 필요.
  ```bash
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock --network host \
    -v "$SUT:/sut" -v "$PWD/out:/out" ghcr.io/<owner>/graph-rag-builder:<v> \
    build --sut-src /sut/src/main/java --sut-jar /sut/build/libs/app.jar \
          --sut-compose /sut/docker-compose.yml --out /out/graph
  ```

### 5.3 이미지 발행

- `release.yml`에 Docker 잡 추가: `docker/build-push-action`으로
  `ghcr.io/<owner>/{test-generator,graph-rag-builder}:<tag>` push. 권한 `packages: write`.
- 신규 파일: `docker/test-generator.Dockerfile`, `docker/graph-rag-builder.Dockerfile`(현재 `docker/` 없음).

### 5.4 실행 자산 (생성 테스트를 컴파일·실행하려면 필요)

CLI만으로는 생성된 테스트를 **돌릴 수 없다**. 다음을 prebuilt로 함께 제공한다.

- **`testlib-<v>.jar`** (Release 자산): 생성 테스트가 import → 컴파일 classpath에 필요.
  `:testlib:jar`로 산출. (선택: generator zip의 `lib/`에 동봉도 가능하나, 테스트는 별 프로젝트에서
  컴파일되므로 독립 jar가 명확.)
- **실행 환경 이미지** (GHCR push): `socket-mock-server`, `test-state-dashboard`
  (둘 다 bootJar 모듈) → `ghcr.io/<owner>/{socket-mock-server,test-state-dashboard}:<tag>`.
  `docs/06`의 docker-compose가 이 이미지를 참조하게 하면 사용자가 빌드 없이 실행 환경을 띄운다.
- **OTEL agent**(`opentelemetry-javaagent`): 생성 테스트 실행 시 **SUT에 부착**(baggage 전파).
  Release 자산으로 `otel-javaagent.jar`를 올리거나, 실행용 compose가 자산에서 받게 한다.
  (builder 자체 분석용 agent는 §2대로 builder 산출물에 이미 포함 — 혼동 금지.)

## 6. 문서 변경 (구현 시 동반)

- `README.md` · `docs/00-getting-started.md`: **권장 경로를 "prebuilt 받기"로 선두 배치**.
  요구사항을 도구별로 **분리**한다 — generator zip/이미지: **JRE 17만**(Docker 불필요);
  builder zip/이미지: **JRE 17 + Docker 데몬 + SUT 소스/jar/compose**. 그다음 ② Docker 이미지,
  ③ (advanced) 소스 빌드(현재의 `./gradlew :...:run`)를 둔다.
- 생성 테스트 실행 안내에 testlib jar(컴파일) + 실행 환경 이미지/OTEL 자산(§5.4) 경로 추가.
- `docs/06-test-environment.md`: OTEL agent 명칭을 실제 빌드명 `otel-javaagent.jar`로 통일
  (현재 `opentelemetry-javaagent.jar` 표기와 불일치).

## 7. 수용 / E2E 테스트 (정의된 done)

배포의 done = 아래 수용 테스트가 모두 통과. 검증의 핵심은 **도구 저장소 소스·Gradle 없이**,
릴리스 산출물 + 별도 fixture SUT(소스/jar/compose)만으로 동작하는가이다. (제거 대상은 "도구 소스
빌드"이지 "SUT 입력"이 아님 — SUT fixture는 정당한 입력이다.)

> fixture 조달: A-E2E는 `samples/order-service`를 임시 디렉터리로 복사해 fixture로 쓴다.
> 그 boot jar는 **테스트 셋업의 일회성 `./gradlew :samples:order-service:bootJar`** 로 만든다 —
> 이는 *도구*가 아니라 *분석 대상 SUT*를 준비하는 것이라 "도구 소스 빌드 없음" 원칙과 모순되지 않는다.

### A (distZip)
- **A-E2E-1 (builder)**: 클린 임시 디렉터리에 builder distZip만 풀고(도구 Gradle/소스 없음),
  `bin/graph-rag-builder build --sut-src ... --sut-jar ... --sut-compose ... --out ...`로
  fixture SUT 분석 → `graph.json` 생성. 환경: JRE 17 + Docker.
- **A-E2E-2 (generator, Docker 없음)**: A-E2E-1의 `graph.json`으로
  `bin/test-generator generate` → 테스트 `.java` 생성.
- **A-E2E-3 (생성 테스트 컴파일)**: A-E2E-2 산출 `.java`를 `testlib-<v>.jar`(+RestAssured/JUnit)
  classpath로 **javac 컴파일 성공**. (testlib 자산이 실제로 컴파일을 가능케 하는지 확인.)
- **A-CI**: release 워크플로가 pre-release 태그에서 두 zip + 실행 자산을 Release에 첨부(실측 1회).

### D (Docker 이미지)
- **D-E2E-1 (generator 이미지)**: 호스트에 Java 없이 `docker run ... test-generator:<v> generate ...`로
  미리 만든 `graph.json` → `.java` 생성.
- **D-E2E-2 (builder 이미지, Linux)**: `docker run --network host -v docker.sock ... build ...`로
  fixture SUT → `graph.json` 생성. **1차 타깃 = Linux**(GitHub `ubuntu-latest`에서 검증).
  macOS/Windows는 `TESTCONTAINERS_HOST_OVERRIDE` best-effort, 한계 문서화.
- **D-E2E-3 (실행 환경 이미지, 선택 강화)**: `socket-mock-server`/`test-state-dashboard` 이미지로
  `docs/06` compose를 띄워 생성 테스트 1개를 실행 → green. (전 파이프라인이 prebuilt만으로 도는지.)

> 타당성: A-E2E-1/2/3, D-E2E-1은 현재 환경에서 결정적으로 재현 가능. D-E2E-2는 Testcontainers-in-
> container 네트워킹이 호스트 OS에 의존 → 1차 수용을 Linux로 한정. D-E2E-3은 compose가 prebuilt
> 이미지를 참조하도록 바꾼 뒤 검증.

## 8. 단계 순서 (제안)

1. **Phase A-1**: 루트 `version` 주입 + `release.yml`(distZip + `testlib:jar` → Release) + CI 게이트.
   수용: A-E2E-1/2/3 + A-CI.
2. **Phase A-2**: 문서 권장 경로 재배치·요구사항 분리(§6).
3. **Phase D-1**: generator 이미지 + push 잡(installDist 선행). 수용: D-E2E-1.
4. **Phase D-2**: builder 이미지(Linux 우선) + 네트워킹 문서. 수용: D-E2E-2(Linux).
5. **Phase D-3**: 실행 환경 이미지(socket-mock·dashboard) + OTEL 자산 + compose 참조 전환.
   수용: D-E2E-3.

각 Phase는 별도 worktree + PR, 전 게이트(리뷰·회귀 green·문서 동기화) 적용.

## 9. 리스크 / 비목표

- **리스크**: D-2 builder 이미지의 cross-OS 네트워킹(Testcontainers + JaCoCo TCP 모두 `--network host`
  의존). 완화: Linux 1차 + 명시적 OS 한계 문서.
- **리스크**: distZip 런처가 JAVA_HOME 미설정 + PATH에 java 없을 때 실패 → JRE 17 요구 문서화.
- **리스크**: 태그가 SemVer 아닐 때 산출물명/버전 오염 → §4.2 형식 검증으로 방어.
- **비목표(현재)**: jpackage/jlink 자체 번들(옵션 C), GraalVM 네이티브, Maven Central/패키지 매니저 배포.
- **비목표**: builder의 Docker 의존 제거(아키텍처상 불가), builder 입력(SUT 소스/jar/compose) 제거.
