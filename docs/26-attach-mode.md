# 26 — Attach 모드: 사용자 compose에 부착해 분석

도구 1(graph-rag-builder)은 기본적으로 분석 대상(SUT)을 직접 띄운다 — Testcontainers로 DB를,
`java -jar`로 앱을 외부 프로세스로 기동한다([docs/03](03-graph-rag-builder.md) "분석 환경" 참조).

**Attach 모드**는 그 대신 **사용자가 이미 가진 `docker-compose.yml`** 로 SUT를 띄운다. 빌더는
사용자 compose 위에 머지할 **override compose** 를 생성해, SQL 캡처용 로깅·커버리지 에이전트·포트
publish를 app 서비스에 주입한 뒤 `docker compose up` 으로 스택을 올리고 분석이 끝나면 내린다.

## 언제 쓰나

- 운영/CI에서 쓰는 docker-compose 구성 그대로 분석하고 싶을 때 (Testcontainers가 재현하지 못하는
  네트워크·의존 서비스 구성을 compose가 이미 담고 있는 경우).
- 앱이 컨테이너 이미지로만 빌드/실행되고 호스트 `java -jar` 기동이 번거로운 경우.

## 생명주기 (빌더가 up/down을 소유)

빌더가 스택의 기동·종료를 직접 관리한다. 이미 떠 있는 장기 스택에 붙는 방식이 아니다(아래 한계 참조).

1. 사용자 compose + 생성된 override(`<out>/work/attach-override.yml`)를 합쳐
   `docker compose -p grb-attach-<sut-id> -f <user> -f <override> up -d --wait <app-service>` 로
   **app 서비스(+ 그 `depends_on`)만** 기동한다. compose의 무관한 보조 서비스는 빌드/기동하지 않는다.
2. `<app-port>` 의 `<health-path>`(기본 `/actuator/health`)를 폴링해 응답이 2xx + `UP` 이 될 때까지
   기다린다(기본 `--ready-timeout` 120초). `--wait` 는 healthcheck가 없는 서비스를 기다리지 않으므로
   빌더가 직접 폴링한다.
3. `docker compose ... logs --no-log-prefix -f <app-service>` 로 app 컨테이너 로그를 파일로 흘려보내고,
   거기서 Hibernate/MyBatis가 남긴 SQL과 바인딩 값을 byte 오프셋으로 잘라 읽는다.
4. published jacoco 포트로 커버리지를 회수하고 published app 포트로 엔드포인트를 탐색한다.
5. 분석이 끝나면 `docker compose ... down -v` 로 컨테이너와 볼륨을 제거한다.

## 필수·선택 플래그

분석 모드와 공통으로 `--sut-src`, `--sut-jar`, `--sut-compose`, `--out` 은 attach 모드에서도 필수다.
`--sut-jar` 은 인덱싱·분기 분석·커버리지 지문(`CoverageFingerprint`)에 쓰여 attach 모드에서도 반드시
필요하다(없으면 모든 요청이 동일 지문으로 collapse되어 탐색이 무의미해진다).

`--sut-src` 의 멀티 루트(brace/콤마/glob, [docs/03](03-graph-rag-builder.md))는 분석/attach 공통
경로(`SutSrcResolver`)라 attach 모드에서도 그대로 동작한다 — 선택한 소스 루트의 엔드포인트만 담은
부분 그래프가 산출된다(전체 앱은 `--sut-jar` 로 부팅하되 정적 인덱싱만 좁힌다). 검증: `e2e/run-attach-multiroot-e2e.sh`.

| 플래그 | 필수 | 의미 |
|---|---|---|
| `--attach` | 필수 | attach 모드 활성화(값 없는 플래그) |
| `--app-service <name>` | 필수 | compose 내 SUT(app) 서비스명 |
| `--app-port <host-port>` | 필수 | app을 호스트에 publish할 포트 |
| `--jacoco-port <host-port>` | 필수 | jacoco tcpserver를 호스트에 publish할 포트 |
| `--jdbc-url <url>` | 필수 | 호스트에서 본 DB JDBC URL(published DB 포트). DB 사용자/비밀번호는 `--sut-compose` 탐지값을 쓴다 |
| `--app-container-port <port>` | 선택 (기본 `8080`) | app 컨테이너 내부 포트 |
| `--db-service <name>` | 선택 | dialect 탐지에 쓸 DB 서비스 선택(여러 DB 서비스가 있을 때) |
| `--kafka-bootstrap <host:port>` | 선택 | Kafka consumer 탐색용 외부 bootstrap. 미지정 시 Kafka 스킵 |
| `--health-path <path>` | 선택 (기본 `/actuator/health`) | readiness 폴링 경로 |
| `--ready-timeout <seconds>` | 선택 (기본 `120`) | readiness 대기 한도 |

## 생성되는 override가 주입하는 것

override는 사용자 compose의 **app 서비스에만** 다음을 더한다(`<out>/work/attach-override.yml` 에 기록):

- **SQL+바인딩 로깅** — `SPRING_APPLICATION_JSON` 환경변수로
  `logging.level.org.hibernate.SQL=DEBUG`, `logging.level.org.hibernate.orm.jdbc.bind=TRACE`,
  그리고 SUT의 MyBatis mapper namespace들을 `TRACE` 로 설정한다. 이 로그를 app 컨테이너 로그에서
  잘라 SQL과 바인딩 값을 캡처한다.
- **에이전트 볼륨** — 호스트의 agents 디렉터리(`<out>/work/agents`, jacoco/otel jar)를 컨테이너의
  `/grb-agents:ro` 로 마운트한다.
- **에이전트 활성화** — `JAVA_TOOL_OPTIONS` 로 jacoco tcpserver 에이전트(`address=*` 로 컨테이너의
  모든 인터페이스에 bind → published 포트로 호스트에서 dump 가능)와 OpenTelemetry javaagent를 켠다.
- **OTEL 환경변수** — trace 모드([docs/06](06-test-environment.md) "trace 모드" 절)에 따라 다르다.
  `--trace-mode none` 이면 트레이스 저장을 끄고 baggage 전파만 사용한다(`OTEL_TRACES_EXPORTER=none` …).
  기본 `--trace-mode otel` 이면 아래 "attach OTEL 네트워킹" 대로 DB span을 호스트 리시버로 보낸다.
- **포트 publish** — `<app-port>:<app-container-port>` 와 `<jacoco-port>:6300` 을 호스트로 연다.

### attach OTEL 네트워킹

기본 `otel` 모드에서 빌더는 분석 동안만 호스트에 OTLP 리시버를 띄우고, 컨테이너 SUT의 OTEL agent가
거기로 DB span을 보내게 override를 구성한다.

- 리시버는 컨테이너에서 도달해야 하므로 호스트의 모든 인터페이스(`0.0.0.0`)에 bind한다.
- override가 app 서비스에 `extra_hosts: ["host.docker.internal:host-gateway"]` 를 추가하고
  (Docker 20.10+ 필요), `OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:<port>` 로 보낸다.
- 넓어진 노출을 막기 위해 빌더가 실행마다 1회용 토큰을 만들어 `OTEL_EXPORTER_OTLP_HEADERS` 로 전달하고,
  리시버는 그 토큰이 맞는 요청만 받는다(불일치는 거부).
- batch insert에서 바인딩이 누락되지 않도록 `hibernate.jdbc.batch_size=0` 을 함께 주입한다.

Docker 20.10 미만이면 `host.docker.internal:host-gateway` 가 동작하지 않아 캡처가 호스트 리시버에
도달하지 못할 수 있다(빌더가 경고를 남긴다). 이 경우 `--trace-mode none` 으로 폴백한다.

### sleuth 모드와 멀티서비스 로그 수집 (`--capture-services`)

레거시 Java8+Sleuth SUT는 `--trace-mode sleuth` 로 붙인다. 빌더가 요청마다 B3 trace-id를 발급해
A 서비스에 주입하고(B3 헤더 / Kafka 레코드 헤더), 그 trace-id가 박힌 로그 라인만 상관해 A→B→C로
이어지는 비동기·서비스간 SQL을 회수한다. OTEL javaagent는 부착하지 않는다(레거시 `brave.Tracing`
빈과 충돌하므로). **전제**: SUT logback이 `%X{traceId}`(또는 동등 MDC 키)를 출력해야 한다(SUT
제공자 책임). 따라서 sleuth 모드에서는 위 "attach OTEL 네트워킹"·OTLP 리시버 배선이 필요 없다.

비동기로 B→C 호출이 일어나는 멀티서비스 구성은 `--capture-services a,b,c` 로 여러 컨테이너 로그를
한 파일로 인터리브 tail해 함께 상관한다. 미지정 시 `--app-service` 한 컨테이너만 본다.

> **egress(외부 HTTP) 캡처와 zipkin export — 빌더가 자동으로 켠다:**
> sleuth 모드의 egress(외부 다운스트림 호출) 발견은 SUT가 내보내는 Brave CLIENT(zipkin) span을
> 호스트 Zipkin 리시버가 받아 이뤄진다(`BuilderCli` sleuth 분기 → `EgressCollector.forMode`).
> 빌더는 sleuth 모드에서 다음 3개를 SUT app 컨테이너에 자동 주입한다
> (`AnalysisEnvironment.sleuthZipkinEnv`):
> `SPRING_ZIPKIN_BASEURL`(호스트 리시버 주소), `SPRING_ZIPKIN_SENDER_TYPE=web`,
> 그리고 **`SPRING_ZIPKIN_ENABLED=true`**. 마지막 항목이 핵심이다 — BASEURL만 주입하고 export가
> 꺼져 있으면 span이 발행되지 않아 egress 캡처가 0건이 되므로, 빌더가 export 자체를 강제로 켜
> SUT 측 설정 의존을 없앤다. BASEURL이 빌더 리시버로 리다이렉트돼 있고 분석 종료 시 override가
> 걷히므로 분석 수명주기 밖 영향은 없다(SQL 로그 상관은 B3 헤더로 별개 동작). `SPRING_ZIPKIN_ENABLED`는
> 레거시 Spring Cloud Sleuth 프로퍼티이며, sleuth 모드 대상(레거시 Java8+Sleuth SUT)에 맞다.
> SUT가 다른 경로로 export를 명시적으로 끄는 특수 케이스라면 `--sut-env`로 덮어쓸 수 있다.

### 외부 HTTP(downstream) 캡처

SUT가 호출하는 외부 HTTP 서비스를, 빌더가 띄운 capture WireMock으로 우회시켜
**캡처된 외부 호출(`CapturedHttpCall`)** 로 graph.json에 기록한다. 분석 모드와 동일한
`--external-stubs` / `--sut-env {{wiremock}}` 플래그를 attach 모드에서도 그대로 쓴다.

| 플래그 | 의미 |
|---|---|
| `--external-stubs <dir>` | 외부 서비스의 minimal valid 응답을 담은 WireMock 스텁(`*.json`) 디렉터리. 운영자가 제공 |
| `--sut-env KEY={{wiremock}}[,K2=V2]` | app 컨테이너에 주입할 환경변수. 값의 `{{wiremock}}` 가 capture WireMock 주소로 치환된다 |

동작:

- 빌더가 분석 동안만 호스트에 capture WireMock을 띄운다. OTLP 리시버와 마찬가지로 컨테이너에서
  도달해야 하므로 모든 인터페이스(`0.0.0.0`)에 bind하고, app 서비스에는 (otel 여부와 무관하게 항상)
  `extra_hosts: ["host.docker.internal:host-gateway"]` 가 주입된다.
- `--sut-env` 값의 `{{wiremock}}` 는 컨테이너가 도달 가능한
  `http://host.docker.internal:<port>/<per-run-token>` 로 치환되어 app에 전달된다. SUT는 외부 서비스의
  base URL을 이 환경변수로 읽어야 한다(예: `EXTERNAL_INVENTORY_URL`).
- 넓어진 노출을 막기 위해 빌더가 실행마다 1회용 토큰을 만들어 **URL 경로 prefix**(`/<token>`)로 쓴다
  (SUT는 outbound 헤더를 제어하지 못하므로 헤더 토큰 대신 base URL로 강제). capture WireMock은 그 prefix가
  맞는 요청만 받고(불일치는 401), 캡처 기록 시 prefix를 벗겨 깨끗한 경로만 남긴다. 토큰은 캡처된
  `CapturedHttpCall.urlPath` 에 새지 않는다 — 그래야 생성된 테스트(토큰 없는 환경에서 실행)의 mock이
  맞는다.
- OTEL 네트워킹과 같은 `host.docker.internal` 경로를 쓰므로 **Docker 20.10+** 가 필요하다. 미만이면
  컨테이너가 호스트 WireMock에 도달하지 못해 외부 HTTP 캡처가 0건이 된다(빌더가 host-gateway 경고를 남김).
- 캡처된 외부 호출은 [docs/06](06-test-environment.md)의 HTTP mock 파이프라인을 따라 생성 테스트에서
  런타임 WireMock 스텁으로 재현된다.

예:

```bash
./gradlew :graph-rag-builder:run --args="build \
  --sut-src samples/order-service/src/main/java \
  --sut-jar samples/order-service/build/libs/order-service.jar \
  --sut-compose e2e/docker-compose.yml \
  --out e2e/.attach-exthttp-out --sut-id order-exthttp \
  --attach --app-service app --app-port 58081 --jacoco-port 16301 \
  --jdbc-url jdbc:postgresql://localhost:56432/app --db-service postgres \
  --external-stubs e2e/external-stubs \
  --sut-env EXTERNAL_INVENTORY_URL={{wiremock}}"
```

전체 파이프라인 예는 `e2e/run-attach-ext-http-e2e.sh` 를 참고한다.

### 사전 조건

- compose에 SUT app 서비스가 있고, 그 서비스명을 `--app-service` 로 지정한다.
- app의 JVM이 `JAVA_TOOL_OPTIONS` 를 존중한다(Spring Boot 표준 컨테이너면 충족).
- DB·app·jacoco의 published 포트가 호스트에서 도달 가능해야 한다(`--jdbc-url`, `--app-port`,
  `--jacoco-port` 가 가리키는 포트).

## v1 한계

attach v1은 아래를 지원하지 않는다(조용히 누락하지 않고 명시한다).

1. **`JAVA_TOOL_OPTIONS` 와 `SPRING_APPLICATION_JSON` 은 override가 교체한다.** docker compose는
   스칼라 값을 머지하지 않고 치환하므로, override의 `JAVA_TOOL_OPTIONS`·`SPRING_APPLICATION_JSON` 이
   사용자 compose의 같은 키를 덮어쓴다. 그래서 otel 에이전트는 override가 스스로 포함한다.
   **SUT가 자기 앱 설정을 `SPRING_APPLICATION_JSON` 으로 주입하는 구성은 v1에서 지원하지 않는다** —
   그런 설정은 attach 전에 개별 환경변수(예: `SPRING_DATASOURCE_URL`)로 옮겨야 한다.
2. **`--sut-compose` 에 인식 가능한 DB 서비스 이미지가 있어야 한다**(postgres/mysql/mariadb).
   dialect 탐지가 분석 모드와 동일하게 compose의 DB 이미지에서 출발한다.
3. **Kafka는 `--kafka-bootstrap` 이 있을 때만.** 미지정 시 Kafka consumer는 스킵된다(로그로 알림).
4. **fresh-stack 전용.** 빌더가 up/down을 소유한다 — 이미 떠 있는 장기 스택에 붙지 않는다.

## 예시

`e2e/docker-compose.yml`(app 서비스 `app`, postgres published `56432:5432`, app `58080:8080`)을
대상으로 하는 실행 예. `e2e/run-attach-e2e.sh` 과 동일한 호출이다.

```bash
# 1) 인덱싱·분기·지문에 필수인 jar 와 app 이미지 빌드
./gradlew -q :samples:order-service:bootJar
docker compose -p grb-attach-order -f e2e/docker-compose.yml build app

# 2) 빌더 attach 실행
./gradlew :graph-rag-builder:run --args="build \
  --sut-src samples/order-service/src/main/java \
  --sut-resources samples/order-service/src/main/resources \
  --sut-jar samples/order-service/build/libs/order-service.jar \
  --sut-compose e2e/docker-compose.yml \
  --out e2e/.attach-out --sut-id order \
  --attach --app-service app --app-port 58080 --jacoco-port 16300 \
  --jdbc-url jdbc:postgresql://localhost:56432/app \
  --db-service postgres"
```

프로젝트명은 `grb-attach-<sut-id>` 로 정해진다(위 예에서 `grb-attach-order`). teardown 후 잔여 컨테이너가
없어야 한다.

## 커스텀 요청 헤더 (attach·분석 공통)

attach 모드와 무관하게, 탐색이 보내는 모든 REST 요청에 커스텀 헤더를 주입할 수 있다. 매 요청마다
시각이 바뀌는 인증 헤더(예: `X-AuthorizationTime`)를 요구하는 SUT를 위한 기능이다.

### 빌더 플래그

| 플래그 | 의미 |
|---|---|
| `--request-headers-file <path>` | 한 줄에 하나씩 `Name: valueTemplate`. 빈 줄과 `#` 주석은 무시 |
| `--request-headers-on-login` | 인증 로그인 호출에도 같은 헤더를 적용(값 없는 플래그) |

값 템플릿은 `{{now:<java.time 패턴>}}` 를 지원한다. **요청 시점마다** `Asia/Seoul` 기준 현재 시각으로
치환되고, 나머지 리터럴은 그대로 둔다. 예:

```
# e2e/.auth-headers.txt
X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900
```

위 파일이면 매 요청마다 `X-AuthorizationTime: 202606171430050900` 같은 값이 붙는다(`0900` 은 리터럴).

```bash
./gradlew :graph-rag-builder:run --args="build --sut-src <src> --sut-jar <jar> \
  --sut-compose <compose> --out <dir> \
  --request-headers-file e2e/.auth-headers.txt --request-headers-on-login"
```

### 생성된 테스트도 같은 헤더를 보내려면

빌더가 보낸 헤더는 **생성된 테스트가 자동으로 따라 보내지 않는다.** 기존 `AUTH_*` 환경변수 관례와
동일하게, 테스트 실행 환경에 환경변수를 지정한다:

- `REQUEST_HEADERS` — 빌더 파일과 같은 형식(`Name: valueTemplate`)을 줄바꿈으로 구분해 나열.
- `REQUEST_HEADERS_ON_LOGIN` — (선택) 설정되어 있으면 로그인 호출에도 적용.

testlib(`RestAssuredHelper`)가 이 값을 읽어 매 요청마다 `{{now:...}}` 를 다시 전개해 보낸다. 빌더 탐색과
생성 테스트가 같은 헤더 규칙을 공유하므로, 헤더를 강제하는 SUT에서 탐색과 재실행이 모두 통과한다.
전체 파이프라인 예는 `e2e/run-auth-headers-e2e.sh` 를 참고한다.
