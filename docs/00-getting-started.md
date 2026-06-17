# 00 — 시작하기

처음 받은 사람이 이 순서대로 따라가면, 도구를 한 번 돌려보고(트랙 A) 자기 Spring 앱에
적용(트랙 B)할 수 있다. 개념·아키텍처를 먼저 읽고 싶으면 [01-overview](01-overview.md)와
[문서 지도](README.md)로 간다.

## 이 도구가 하는 일

도구 두 개로 나뉜다.

1. **graph-rag-builder**(도구 1)가 대상 앱(SUT, System Under Test)을 외부 프로세스로 띄워
   HTTP로 호출해 보면서, 코드의 사실(엔드포인트·분기·발행 SQL·외부 호출·DB 스키마)을
   `graph.json`으로 캡처한다.
2. **test-generator**(도구 2)가 그 `graph.json`과 요청 한 건(어느 엔드포인트의 테스트를
   원하는지)을 입력받아 RestAssured/JUnit5 테스트 코드를 만든다.

두 도구 안에 LLM은 없다. 같은 입력이면 같은 결과가 나온다. 자세한 구조는
[02-architecture](02-architecture.md).

## 사전 준비

- **JDK 17.** 빌드는 `gradle.properties`의 `org.gradle.java.home`에 적힌 JDK를 쓴다. 그 값이
  이 저장소를 만든 머신의 경로로 고정돼 있으니, 다른 머신에서는 그 줄을 자기 JDK 17 경로로
  바꾸거나 `JAVA_HOME`을 JDK 17로 지정한다. 셸의 기본 `java` 버전이 11이어도 빌드는 위 설정의
  JDK 17을 쓰므로 상관없다.
- **Docker(실행 중).** 도구 1은 Testcontainers로 DB·Kafka를, 생성된 테스트는 docker compose로
  실행 환경을 띄운다. 먼저 `docker info`가 성공하는지 확인한다.
- 인터넷 연결. 처음 실행 시 Postgres·Kafka 등 컨테이너 이미지를 내려받는다.

## 트랙 A — 데모 한 번 돌려보기

저장소에 들어 있는 샘플 앱(`samples/order-service`)으로 전 사이클을 돌린다.

```bash
./e2e/run-e2e.sh
```

이 스크립트가 하는 일(5단계):

1. 샘플 SUT와 보조 서비스의 jar를 빌드한다.
2. **도구 1**: SUT를 외부 프로세스로 띄우고(Testcontainers DB·Kafka + 분석용 WireMock +
   JaCoCo) 엔드포인트를 호출해 분기를 탐색하고 `graph.json`을 만든다.
3. **도구 2**: 엔드포인트별 요청 파일(`e2e/request-*.json`)마다 테스트 클래스를 생성한다.
4. docker compose로 실행 환경을 띄우고 생성된 테스트를 모두 실행한다.
5. 컨테이너를 정리한다.

성공하면 마지막 줄에 다음이 나온다.

```
✅ E2E PASS — tests=53 skipped=0 failures=0 errors=0
```

산출물 위치:

| 경로 | 내용 |
|---|---|
| `e2e/out/graph/graph.json` | 도구 1이 캡처한 사실(엔드포인트·분기·SQL·스키마) |
| `e2e/out/graph/exploration-report.json` | 탐색 커버리지 리포트 |
| `e2e/out/generated/` | 도구 2가 생성한 테스트 `.java` |

## 트랙 B — 내 Spring 앱에 적용

자기 앱에는 도구 1·2를 직접 호출한다. 받는 방법은 두 가지이고 **둘 다 소스 빌드가 필요 없다**.
요구사항은 도구별로 다르다 — **test-generator는 Docker 불필요**, **graph-rag-builder는 Docker 데몬 필요**.

**A. Release zip (권장).** [Releases](https://github.com/baekchangjoon/graph-rag-test-generator/releases)에서
받아 압축을 푼다. `test-generator`는 **JRE 17만**, `graph-rag-builder`는 **JRE 17 + Docker**.

```bash
unzip test-generator-0.2.0.zip      # → test-generator-0.2.0/bin/test-generator
unzip graph-rag-builder-0.2.0.zip   # → graph-rag-builder-0.2.0/bin/graph-rag-builder
```

**B. 컨테이너 이미지 (Java 설치도 불필요).** GHCR에서 pull한다.

```bash
docker pull ghcr.io/baekchangjoon/test-generator:0.2.0
docker pull ghcr.io/baekchangjoon/graph-rag-builder:0.2.0
```

> GHCR 패키지가 **public**이면 위 `docker pull`이 익명으로 된다(private면 먼저 `docker login ghcr.io`).

분석 대상 SUT의 **소스·boot jar·docker-compose**는 어느 방법이든 도구 입력으로 필요하다(§1).

아래 예시는 A(zip 런처) 기준이다. B(이미지)로 실행하려면 `bin/...`을 `docker run`으로 바꾼다:

```bash
# generator
docker run --rm -v "$PWD:/w" -w /w ghcr.io/baekchangjoon/test-generator:0.2.0 generate ...
# builder (Linux: docker.sock + --network host)
docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$SUT:/sut" -v "$PWD/out:/out" ghcr.io/baekchangjoon/graph-rag-builder:0.2.0 build ...
```

> 소스에서 빌드해 쓰려면(advanced): `./gradlew :graph-rag-builder:run --args="build ..."`,
> `./gradlew :test-generator:run --args="generate ..."`.

### 1. SUT 준비

- 운영과 같은 방식으로 만든 **boot jar** (`./gradlew bootJar` 또는 `mvn package`)
- **소스 디렉터리**(`src/main/java`). 리소스는 생략 시 소스 옆 `src/main/resources`로 가정한다(`--sut-resources`로 지정).
- 앱이 쓰는 DB를 정의한 **docker-compose.yml** (도구 1이 여기서 DB 종류를 감지한다)

### 2. 도구 1 — 사실 캡처

```bash
./graph-rag-builder-<v>/bin/graph-rag-builder build \
  --sut-src   <앱>/src/main/java \
  --sut-resources <앱>/src/main/resources \
  --sut-jar   <앱>/build/libs/<app>.jar \
  --sut-compose <앱>/docker-compose.yml \
  --out       ./out/graph
```

자주 쓰는 옵션:

| 옵션 | 언제 |
|---|---|
| `--auth-login-path /api/auth/login --auth-user U --auth-pass P` | 로그인 토큰이 필요한 보호된 엔드포인트가 있을 때 |
| `--external-stubs <dir> --sut-env KEY={{wiremock}}` | 앱이 외부 HTTP를 호출해서 스텁이 필요할 때 |
| `--with-redis` / `--with-kafka` | 앱이 Redis·Kafka를 쓸 때 |
| `--sut-java-home <jdk>` | SUT를 다른 JDK로 띄워야 할 때 |

끝나면 `./out/graph/graph.json`이 생긴다.

### 3. 테스트로 만들 엔드포인트 찾기

`graph.json`의 `endpoints` 목록에서 `id`를 확인한다. `id`는 **HTTP 메서드 + 경로**를
소문자로 바꾸고 영숫자가 아닌 구간을 `-`로 바꾼 값이다.

| 메서드 + 경로 | endpointId |
|---|---|
| `POST /api/orders` | `post-api-orders` |
| `GET /api/orders/{id}` | `get-api-orders-id` |

### 4. 요청 파일 작성

엔드포인트 하나당 JSON 한 개를 만든다.

```json
{
  "endpointId": "post-api-orders",
  "testClassName": "OrdersPostTest",
  "packageName": "io.graphrag.generated",
  "authMode": "REAL"
}
```

`authMode`는 보호된 엔드포인트면 `REAL`(토큰 발급 코드 포함), 인증이 꺼져 있으면 `DISABLED`.

### 5. 도구 2 — 테스트 생성

```bash
./test-generator-<v>/bin/test-generator generate \
  --request ./request-orders.json \
  --graph   ./out/graph \
  --out     ./out/generated
```

`./out/generated`에 테스트 `.java`가 생긴다.

### 6. 생성된 테스트 실행

생성된 테스트는 `io.graphrag.testlib.*`를 import하므로, 컴파일하려면 Releases의
**`testlib-<v>.jar`** 를 자기 테스트 프로젝트의 의존성에 더한다(RestAssured·JUnit 등 표준 테스트
의존은 평소처럼 추가).

실행하려면 운영과 같은 DBMS·WireMock·socket-mock·대시보드가 떠 있는 실행 환경이 필요하다. 그 구성은
[06-test-environment](06-test-environment.md)를 따른다. 데모(`e2e/`)의 `docker-compose.yml`과
`build.gradle.kts`가 그 구성의 동작 예시다.

## 막히면

| 증상 | 원인 / 해결 |
|---|---|
| `Cannot connect to the Docker daemon` | Docker가 실행 중이 아니다. `docker info`로 확인 후 Docker를 켠다. |
| `invalid Gradle JDK` / 17이 아님 | `gradle.properties`의 `org.gradle.java.home`이 이 머신에 없는 경로다. 자기 JDK 17 경로로 바꾸거나 `JAVA_HOME`을 JDK 17로 지정한다. |
| `address already in use` | 직전 실행의 컨테이너가 포트를 잡고 있다. `docker compose -f e2e/docker-compose.yml down -v` 후 다시 실행. (`run-e2e.sh`는 이를 자동 재시도한다.) |
| 이미지 pull 지연 | 처음 실행은 컨테이너 이미지를 내려받느라 느리다. 두 번째부터 빨라진다. |

## 다음 단계

- 전체 문서 지도: [docs/README.md](README.md)
- 용어 정의: [glossary.md](glossary.md)
- 입력 생성 원리: [23-input-generation-flow](23-input-generation-flow.md)
