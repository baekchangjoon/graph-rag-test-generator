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
- **OTEL 환경변수** — 분석 모드와 동일하게 트레이스 저장은 끄고 baggage 전파만 사용
  (`OTEL_TRACES_EXPORTER=none`, `OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`,
  `OTEL_PROPAGATORS=tracecontext,baggage`, `OTEL_SERVICE_NAME=<sut-id>`).
- **포트 publish** — `<app-port>:<app-container-port>` 와 `<jacoco-port>:6300` 을 호스트로 연다.

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
3. **외부 HTTP(downstream) 캡처 미지원.** 컨테이너 SUT가 호스트의 임베디드 WireMock에 기본 도달하지
   못하므로, attach 모드는 캡처된 외부 HTTP 호출을 반환하지 않는다.
4. **Kafka는 `--kafka-bootstrap` 이 있을 때만.** 미지정 시 Kafka consumer는 스킵된다(로그로 알림).
5. **fresh-stack 전용.** 빌더가 up/down을 소유한다 — 이미 떠 있는 장기 스택에 붙지 않는다.

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
