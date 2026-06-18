# 레거시 Eventuate Tram 샘플 스택 + 라이브 E2E 설계 (Spec 1)

- 작성일: 2026-06-18 (3-model 리뷰 반영 + DB=MySQL 확정 rev.3)
- 상태: Draft (사용자 검토 대기)
- 관련: Spec 2 = sleuth trace-mode 구현(PR #60, 브랜치 `worktree-poc-legacy-async-sql-capture`).
  본 spec은 그 **최종 수용 게이트**.
- 브랜치: `worktree-legacy-tram-sample` (main 기준).

## 1. 목적 / 수용 게이트

이 샘플의 유일한 존재 이유는 **Spec 2(sleuth trace-mode)의 라이브 수용 게이트**다. 합성 픽스처 단위/통합으로만
검증한 sleuth 경로를 충실한 레거시 스택에서 실증한다. 핵심은 **R1**:

> A에 주입한 B3 trace-id가 **동기 HTTP(A→B) + 비동기 Tram(B→C, Eventuate CDC 경유)** 경계를 **동일 값으로**
> 넘어 C 컨슈머 스레드 MDC까지 전파되어, C의 Hibernate 5 SQL 로그 라인에 같은 trace-id로 찍히는가?

R1이 참이면 sleuth가 비동기 cross-service SQL을 요청 단위로 회수할 수 있음이 라이브로 확정된다. R1이 거짓이면
그 자체가 valid finding — sleuth의 R1 가정이 깨진 것이므로 probe/payload 상관(백업)으로 회귀할 근거가 된다.
**이 샘플의 1차 가치는 R1의 진위를 드러내는 것.**

## 2. 범위와 분해 / 빌더 의존성

- **이 spec (Spec 1)**: `samples/legacy-tram/` 자급 docker-compose 스택(3 Boot2/Java8 서비스 + Kafka +
  **MySQL** + Eventuate MySQL-binlog CDC 서비스) + 라이브 E2E 런북(`e2e/run-legacy-tram-sleuth-e2e.sh`).
- **DB = MySQL (binlog CDC)**: Eventuate Tram의 클래식·최다 문서화 경로. 레거시 타깃 현실성(euc-kr collation 등)도
  MySQL이 자연스럽다.
- **빌더 의존성 (둘 다 명시·치명)**:
  1. **sleuth 지원**: 이 worktree는 main 기준이라 빌더에 `--trace-mode`/`--capture-services`가 **없다**(Spec 2/PR
     #60 도입). E2E 런북은 **PR #60 머지된 빌더(또는 그 브랜치 빌드)** 가 필요. 런북이 시작 시 fail-fast 점검
     (`graph-rag-builder build --help`에 두 플래그 존재 확인, 없으면 즉시 비-0 종료 + 안내).
  2. **빌더 MySQL 스키마 지원(선행 빌더 과제)**: 빌더 `SchemaExtractor`가 JDBC 메타데이터를
     `getTables(null, "public", ...)`로 읽어 **MySQL에선 0 테이블**이 된다(스키마가 Hibernate auto-DDL이든
     init.sql이든 *생성*되는 것과 무관 — *되읽기* 경로의 Postgres 전용 하드코딩). `DbConfig.Type`에 이미 `MYSQL`/
     `MARIADB`가 선언돼 있으므로 이는 미완성 지원. **`SchemaExtractor`를 dbType별로 보정**한다(POSTGRES→"public"
     유지, MYSQL/MARIADB→`connection.getCatalog()` 카탈로그 + schema null). Postgres 경로 회귀 없음. 이 보정은
     Spec 1 구현 계획의 **빌더 선행 과제**로 둔다(빌더에 반영; PR #60 또는 별도 작은 builder 변경).
- **Out of scope**: sleuth 캡처 로직 변경(Spec 2 완료), CI 통합(로컬 런북만), probe/window 대체 경로 구현(R1
  실패 시 별도 후속), 한글/euc-kr 데이터 단계, 샘플의 광범위 비즈니스 로직, 메인 Gradle 멀티빌드 통합,
  `SleuthLogCapture` 델타-커서 최적화.

## 3. 도메인 흐름 (각 홉이 SQL을 낸다)

최소이되 세 서비스가 각각 SQL을 내고, 한 개의 동기 HTTP 홉 + 한 개의 비동기 Tram 홉을 거친다.

| 서비스 | 역할 | SQL | 다음 홉 |
|---|---|---|---|
| **A `order-web`** | HTTP entry. `POST /orders {userId, amount}` | `orders` insert (H5@A) | 동기 HTTP `POST /reservations` |
| **B `reservation`** | `POST /reservations {orderId,userId,amount}` | `reservations` insert (H5@B) + **outbox `message` insert(같은 TX)** | Eventuate Tram 이벤트 `OrderReserved` 발행 |
| **C `ledger`** | `@EventHandler(OrderReserved)` (Tram 구독) | `received_messages` dedup + `ledger_entries` insert (**타깃 비동기 H5@C**) | — |

- A는 `orders` insert 후 B를 동기 호출하고 **HTTP 202**(비동기 — 응답 시점엔 C SQL 미완; sleuth await/quiescence가 흡수) 반환.
- 검증 대상 핵심 SQL은 **C의 `ledger_entries` insert**(Tram 경계 너머 실행).

## 4. 데이터 설계 / 스키마 생성

- **단일 MySQL 인스턴스, 서비스별 분리 DB**: `orderdb`(A), `reservationdb`(B), `ledgerdb`(C).
  Eventuate outbox 패턴상 **`message` 테이블은 발행 서비스의 비즈니스 DB와 같은 DB**(같은 트랜잭션 경계)에
  있어야 하므로 B의 `reservationdb`에 Eventuate `message` 테이블, C의 `ledgerdb`에 `received_messages`.
- **스키마 생성**:
  - **비즈니스 테이블(JPA 엔티티) = Hibernate `ddl-auto=update`** — 앱 부팅 시 datasource DB에 자동 생성
    (`orders`, `reservations`, `ledger_entries`). 별도 DDL 불필요.
  - **Eventuate 인프라 테이블(`message`/`received_messages`)** 은 두 경로를 **병행**해 init.sql 유무·실패와
    무관하게 보장한다(자급 샘플 불변식):
    - **1순위 = init.sql**: Eventuate Tram 공식 스키마를 MySQL 컨테이너 `/docker-entrypoint-initdb.d/`로 적용.
    - **폴백(필수) = JPA `@Entity` 매핑**: 해당 테이블을 그대로 미러링한 엔티티를 서비스에 포함해 `ddl-auto=update`가
      **없으면 생성**하게 한다 — B(`reservationdb`)에 `message` 엔티티, C(`ledgerdb`)에 `received_messages` 엔티티.
      `ddl-auto=update`라 init.sql이 이미 만든 경우 Hibernate가 기존 테이블을 보고 **no-op(멱등)**, init.sql이
      없으면 Hibernate가 생성. **엔티티는 Eventuate 핀 버전의 공식 스키마와 정확히 일치**해야 한다(테이블/컬럼명,
      타입은 `@Column(columnDefinition=...)`로 명시; 길이/타입 불일치 시 Eventuate insert가 실패·절단될 수 있으므로
      핀 버전 스키마에서 도출). (Eventuate CDC 자신의 오프셋/리더십 테이블은 CDC 서비스 소관이라 별도.)
  - **검증**: 샘플 smoke(또는 런북 사전단계)에서 각 서비스 부팅 후 자기 DB에 해당 Eventuate 테이블이 존재함을
    확인(init.sql을 의도적으로 비활성화한 변형으로 폴백 경로도 1회 확인).
- **트랜잭션 경계**: B의 `reservations` insert + `OrderReserved` 발행(=`message` insert)은 **같은 Hibernate
  트랜잭션**(Eventuate 트랜잭셔널 아웃박스) → 둘 다 **요청 trace-id가 박힌** 같은 스레드.
- **C 멱등성**: Tram이 `received_messages`로 중복 처리 방지(at-least-once 대비). `ledger_entries`는 `order_id`
  unique로 추가 가드(선택).
- **빌더 `--jdbc-url`**: dialect 감지 + 스키마 되읽기용 `jdbc:mysql://localhost:<port>/orderdb`(A).
  (§2 빌더 MySQL 스키마 보정이 적용돼야 0 테이블이 아니게 된다.) B/C는 각자 `SPRING_DATASOURCE_URL`로 자기 DB.

## 5. 컴포넌트 (docker-compose, `samples/legacy-tram/`)

인프라:
- `kafka`(+zookeeper 또는 KRaft 단일) — Eventuate CDC가 메시지를 publish하는 브로커.
- `mysql` — **binlog 활성**(`--server-id`, `--log-bin`, `--binlog-format=ROW`). 3개 DB + Eventuate 테이블
  (init.sql). CDC 전용 유저는 `REPLICATION SLAVE`/`REPLICATION CLIENT` + 대상 DB 접근 권한.
- `eventuate-cdc-service` — 공식 이미지(`eventuateio/eventuate-cdc-service`). **MySQL binlog** 모드로 outbox
  `message` 변경을 tail → Kafka 릴레이. 필요한 env(구현 계획에서 확정, 공식 `docker-compose-cdc-mysql-binlog`
  템플릿 기준): `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `EVENTUATELOCAL_KAFKA_BOOTSTRAP_SERVERS`,
  `EVENTUATE_CDC_TYPE`, `EVENTUATELOCAL_CDC_MYSQL_BINLOG_CLIENT_UNIQUE_ID`(server_id), reader/leadership 설정.
- **기동 순서(치명)**: `eventuate-cdc-service.depends_on = [mysql(healthy), kafka(healthy)]`, 그리고
  **`ledger(C).depends_on = [eventuate-cdc-service(started), kafka, mysql]`**. 이렇게 해야 빌더의
  `docker compose up -d --wait <capture-services>`(= order-web,reservation,ledger)가 의존성 그래프로 **CDC를
  끌어올린다**. CDC가 안 뜨면 B 메시지가 C에 전달 안 돼 R1이 "전파 실패가 아닌 이유"로 거짓이 되므로 필수.

애플리케이션(각자 자체 Gradle Java8 toolchain + Dockerfile, **메인 Gradle 멀티빌드 미포함**):
- `order-web`(A) / `reservation`(B) / `ledger`(C): **Java 8 + Spring Boot 2.7.x + Hibernate 5 +
  Spring Cloud Sleuth(Brave/B3) + Eventuate Tram**.
- **로그 형식 요구**: logback 패턴이 (1) **`%X{traceId}`** 포함(전제 R2 충족), (2) 로거명·메시지를 **표준 ` : `
  구분자**(Spring Boot 기본)로 분리 — `SqlLogParser`가 prefix를 ` : ` 기준으로 나누고 H5 `org.hibernate.SQL : ...`
  형식을 매칭하기 때문.
- **설정 제약**: 빌더 override가 각 capture-service의 **`SPRING_APPLICATION_JSON`을 교체**하므로, 샘플 서비스는
  앱 설정을 **개별 환경변수**(`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
  `SPRING_JPA_HIBERNATE_DDL-AUTO` 등)로 주되 `SPRING_APPLICATION_JSON`에 의존하지 말 것(override가 덮어씀).
- **버전 핀(잠정 — 구현 계획에서 검증·확정)**: Spring Boot 2.7.x, Spring Cloud(Sleuth 호환 train), Eventuate
  Tram core + `eventuate-tram-spring-cloud-sleuth-integration` 동일 버전, `eventuate-cdc-service` 호환 태그,
  Java 8 toolchain.

## 6. trace 전파 경로 (R1 메커니즘)

```
builder ──B3 멀티헤더 주입──▶ POST /orders (A)        (B3 = X-B3-TraceId/SpanId/Sampled + b3; Spec 2 §7)
  A: Brave HTTP 서버가 B3 수용 → trace 컨텍스트 설정
  A ──동기 HTTP(B3 전파)──▶ POST /reservations (B)
  B: Brave HTTP 서버가 B3 수용 → 그 trace 컨텍스트에서 reservations insert + Tram 이벤트 발행(같은 TX)
     └▶ Eventuate↔Brave 통합이 trace 헤더를 message 행에 기록
  CDC: binlog tail → Kafka publish (메시지의 trace 헤더 보존)
  C: Tram 구독자가 Kafka에서 수신 → Eventuate가 trace 컨텍스트 복원 → 컨슈머 스레드 MDC traceId
     └▶ C logback이 H5 SQL/bind 라인에 동일 trace-id 출력  ◀── R1이 검증하는 지점
```

- **상관 헤더는 B3**(Spec 2 §7: sleuth 모드는 B3 멀티헤더 주입, traceparent 미사용). 빌더 `SleuthLogCapture`가
  요청별 유니크 B3 traceId를 발급·주입하고 그 traceId가 박힌 로그 라인만 상관.
- **1순위 통합(artifact 확인됨)**: 각 서비스에
  **`io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE`** 의존성 추가가 B→C trace 전파 1순위. (Task 2 정정: 구 `...core:eventuate-tram-spring-cloud-sleuth-integration`은 Boot 2.7용 미존재.)
- **폴백(통합 jar 비호환 시) — 커스텀 Tram `MessageInterceptor`**:
  - 발행측(`preSend`): `brave.Tracing.currentTracer().currentSpan().context()`에서 B3를 메시지 헤더
    (`X-B3-TraceId`/`X-B3-SpanId`/`X-B3-Sampled`)로 복사.
  - 수신측(`preHandle`): 그 헤더를 Brave `Propagation.extractor`로 추출 → `tracer.nextSpan(extracted)` →
    try-with-resources `SpanInScope`로 컨슈머 스레드에 활성화(MDC 반영).
- **최대 노력 / R1-거짓 판정**: 1순위 통합 + 폴백 인터셉터까지 시도 후에도 C 로그에 동일 trace-id가 없으면
  **R1=거짓** 판정·문서화 → sleuth probe/window 대체 경로 설계를 후속 트리거.

## 7. builder attach 호출 (런북 내부)

> **사전조건**: PR #60 빌더(`--trace-mode`/`--capture-services`) + §2 빌더 MySQL 스키마 보정. 런북 fail-fast 점검.

```
graph-rag-builder build --attach \
  --sut-compose samples/legacy-tram/docker-compose.yml \
  --app-service order-web --app-container-port 8080 --app-port <hostA> --jacoco-port <hostJ> \
  --jdbc-url jdbc:mysql://localhost:<hostDb>/orderdb \
  --kafka-bootstrap localhost:<hostKafka> \
  --trace-mode sleuth --capture-services order-web,reservation,ledger \
  --sut-src <A 소스> --sut-jar <A bootjar> --out <out>
```

- 빌더가 A 엔드포인트를 인덱싱하고 `POST /orders`에 요청(B3는 SleuthLogCapture가 주입).
- `--capture-services`가 a/b/c 컨테이너 로그를 한 파일로 인터리브 tail(빌더가 appService 자동 포함).
- jacoco 커버리지는 A만(override가 app 서비스에 jacoco agent 주입). C의 비동기 SQL은 trace-id 상관으로 회수.

## 8. E2E 수용 기준 (런북 `e2e/run-legacy-tram-sleuth-e2e.sh`)

double-loop 바깥(수용 = 이 라이브 런북, out-of-process 블랙박스, 프로젝트 최고 가능 레벨). 런북: 빌더 sleuth/스키마
지원 점검 → 스택 up(healthcheck/대기) → 검증 3종 → down. **DoD = 3종 전부 PASS + 실행법 문서화.**

1. **R1 (trace-id 전파, 빌더 독립)**: 알려진 B3 trace-id로 `POST /orders` 1건 curl. **요청 직전 C 로그 offset/marker
   기록**, curl 후 **최대 30s, 250ms 간격 폴링**으로 C(ledger) 컨테이너 로그에서 그 trace-id가 박힌 H5
   `org.hibernate.SQL`/bind(`ledger_entries`) 라인을 찾는다. PASS=30s 내 동일 trace-id로 출현. FAIL=타임아웃 +
   A/B/C/CDC/Kafka 로그·상태 덤프.
2. **builder 캡처**: §7대로 attach 실행 → 출력 `graph.json`의 `POST /orders` 경로에 **적어도** A `orders` insert +
   B `reservations` insert + **B의 Eventuate `message`(outbox) insert(같은 TX·동일 trace-id이므로 정상 귀속,
   노이즈 아님)** + C `ledger_entries` insert가 그 요청에 귀속(정확히 N개가 아니라 **최소 집합**으로 단언).
   (스키마 되읽기가 동작하면 table/column 메타가 채워지는지도 부수 확인.)
3. **인프라 노이즈 배제**: Eventuate CDC가 **백그라운드 스레드**에서 상시 폴링/리더십 갱신하는 SQL(요청 trace-id
   없음)은 캡처 graph에 **미포함**. (B의 outbox insert는 요청 TX라 trace-id가 있어 #2에 포함되는 것과 구분.)

각 검증은 PASS/FAIL을 명확히 출력, 하나라도 FAIL이면 런북 비-0 종료 + 진단 덤프.

## 9. 테스트 / DoD

- **바깥(수용)**: §8의 라이브 런북 3종 PASS.
- **빌더 선행 과제(§2-2)**: `SchemaExtractor` MySQL 카탈로그 보정은 **단위 테스트**(MySQL/MariaDB dbType에서
  getTables가 catalog로 조회됨; Postgres 회귀)로 TDD. 이 단위 테스트 green이 선행.
- **안쪽(샘플)**: 샘플 서비스 자체 단위테스트는 최소(샘플=테스트 픽스처). 각 서비스 부팅·핵심 핸들러 smoke는 선택.
  단, **Eventuate 테이블 부트스트랩 검증은 필수**: (a) init.sql 적용 시 테이블 존재, (b) **init.sql 비활성 변형에서
  JPA 폴백(`ddl-auto=update`)이 동일 테이블을 생성**함을 1회 확인(§4 폴백 불변식 보장).
- CI 미포함(Docker+Kafka+CDC 무게·플래키) — 로컬 런북이 수용 게이트. README/런북에 사전조건(Docker, PR #60
  빌더 + MySQL 스키마 보정)과 실행법 명시.

## 10. 리스크 / 미해결

- **R1 미전파(치명)**: §6의 1순위 통합 + 폴백 인터셉터로도 안 되면 = Spec 2 R1 가정이 깨진 것 → "R1 거짓"
  문서화 + sleuth probe/window 후속 트리거.
- **버전 정합성**: Eventuate Tram ↔ Boot2.7 ↔ Java8 ↔ sleuth-integration ↔ cdc 이미지. 비호환 시 부팅 실패 →
  공식 `docker-compose-cdc-mysql-binlog` 템플릿 기준 검증 조합으로 핀 고정.
- **기동 순서/플래키**: CDC는 MySQL binlog + Kafka ready 후 시작(§5 depends_on + healthcheck). binlog 미활성/
  CDC 유저 권한 누락 시 CDC 부팅 실패 — compose/init.sql에 명시.
- **빌더 MySQL 스키마 보정 미적용 시**: SchemaExtractor가 0 테이블 → seed/스키마 메타 결손(캡처 SQL 자체는
  로그 파싱이라 영향 적으나 그래프 품질 저하). 그래서 §2-2를 선행 과제로 둠.
- **로그 파싱 성능**: 멀티서비스 인터리브 로그가 빠르게 커지면 `SleuthLogCapture` 매 폴링 전체 재스캔이 O(n²).
  본 게이트(단일/소수 요청)엔 충분, 대량 요청 시 델타-커서 필요 — 빌더 후속 추적(범위 밖).
- **인코딩**: 샘플 로그/DB UTF-8. 한글·euc-kr은 직교(별개 후속).

## 11. Out of Scope

sleuth 캡처 로직 변경, CI 통합, probe/window 대체 경로 구현, 한글/euc-kr 데이터 단계, 샘플 광범위 비즈니스
로직·단위테스트, 메인 Gradle 멀티빌드 통합, `SleuthLogCapture` 델타-커서 최적화.

## 12. 3-Model 리뷰 반영 + DB 결정 기록 (2026-06-18)

3-model 교차 리뷰(Claude Sonnet + Gemini 3.5 Flash High + GPT-5.5) 반영:

- **빌더 의존성 명시·fail-fast(전 리뷰어 I1)** → §2. **CDC 기동 depends_on(Sonnet I2)** → §5.
  **Eventuate↔Sleuth 통합 artifact + 폴백(Sonnet I3/GPT I5)** → §6. **CDC/binlog 설정(Sonnet I6)** → §5.
  **R1 대기 예산 30s/250ms(Sonnet I5/GPT I4)** → §8. **데이터 설계(Sonnet I8/GPT I3)** → §4. **outbox SQL
  정상 귀속(Sonnet I7)** → §8 #2. **SPRING_APPLICATION_JSON 제약(Sonnet I4)**, **logback ` : `(Gemini I2)**,
  **B3 계약(GPT I2)**, **상태코드 202(GPT I6)**, **버전 핀(Gemini I4 등)** → §3/§5/§6.
- **DB 결정(MySQL 확정)**: Gemini I1은 "SchemaExtractor가 Postgres 전용이라 MySQL 0 테이블"을 옳게 지적했고,
  rev.2는 Postgres로 우회했으나 **사용자 결정으로 MySQL 확정**. 우회 대신 **빌더 `SchemaExtractor`를 dbType별로
  보정**(MYSQL/MARIADB→catalog)하는 선행 빌더 과제로 정공법 해결(§2-2). `DbConfig.Type`에 이미 MYSQL/MARIADB가
  선언돼 있어 미완성 지원을 완성하는 것이며 Postgres 회귀 없음. (스키마 *생성*은 Hibernate ddl-auto/ init.sql이
  담당 — Gemini 지적은 *되읽기* 경로였음.) **로그 파싱 O(n²)(Gemini I3)** → §10.
