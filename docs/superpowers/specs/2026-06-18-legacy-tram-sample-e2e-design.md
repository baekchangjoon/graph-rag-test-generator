# 레거시 Eventuate Tram 샘플 스택 + 라이브 E2E 설계 (Spec 1)

- 작성일: 2026-06-18 (3-model 리뷰 반영 rev.2)
- 상태: Draft (3-model 리뷰 반영 → 사용자 검토 대기)
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

## 2. 범위와 분해 / 빌더 의존성 (리뷰 I1)

- **이 spec (Spec 1)**: `samples/legacy-tram/` 자급 docker-compose 스택(3 Boot2/Java8 서비스 + Kafka +
  **PostgreSQL** + Eventuate Postgres-WAL CDC 서비스) + 라이브 E2E 런북(`e2e/run-legacy-tram-sleuth-e2e.sh`).
- **빌더 의존성(명시·치명)**: 이 worktree는 main 기준이라 빌더에 `--trace-mode`/`--capture-services`가 **없다**
  (Spec 2/PR #60이 도입). 따라서 E2E 런북은 **PR #60이 머지된 main 빌더, 또는 그 브랜치에서 빌드한 빌더**가
  필요하다. **런북은 시작 시 빌더의 sleuth 지원을 fail-fast 점검**한다(예: `graph-rag-builder build --help`에
  `--trace-mode`/`--capture-services` 존재 확인, 없으면 즉시 비-0 종료 + 안내). 샘플 스택(SUT) 자체는 빌더와
  독립이라 PR #60 없이도 빌드/기동된다.
- **DB 선택(리뷰 Gemini I1 반영)**: **PostgreSQL**. 빌더 `SchemaExtractor`가 `"public"` 스키마를 사용해
  Postgres 경로가 검증돼 있고(기존 샘플/테스트가 `jdbc:postgresql`), MySQL은 카탈로그/스키마 모델이 달라 현재
  빌더에서 0 테이블이 추출된다. Eventuate CDC는 Postgres-WAL(logical replication) CDC를 지원하므로 "Tram+CDC
  최대 충실"은 유지된다. (빌더의 MySQL 스키마 지원은 별개 빌더 갭으로 **본 spec 범위 밖**, 추적만.)
- **Out of scope**: 빌더 코드 변경(Spec 2에서 완료), 빌더 MySQL 스키마 지원, CI 통합(로컬 런북만), probe/window
  대체 경로 구현(R1 실패 시 별도 후속), 한글/euc-kr 데이터 단계, 샘플의 광범위 비즈니스 로직, 메인 Gradle
  멀티빌드 통합.

## 3. 도메인 흐름 (각 홉이 SQL을 낸다)

최소이되 세 서비스가 각각 SQL을 내고, 한 개의 동기 HTTP 홉 + 한 개의 비동기 Tram 홉을 거친다.

| 서비스 | 역할 | SQL | 다음 홉 |
|---|---|---|---|
| **A `order-web`** | HTTP entry. `POST /orders {userId, amount}` | `orders` insert (H5@A) | 동기 HTTP `POST /reservations` |
| **B `reservation`** | `POST /reservations {orderId,userId,amount}` | `reservations` insert (H5@B) + **outbox `message` insert(같은 TX)** | Eventuate Tram 이벤트 `OrderReserved` 발행 |
| **C `ledger`** | `@EventHandler(OrderReserved)` (Tram 구독) | `received_messages` dedup + `ledger_entries` insert (**타깃 비동기 H5@C**) | — |

- A는 `orders` insert 후 B를 동기 호출하고 **HTTP 202**(비동기 — 응답 시점엔 C SQL 미완; sleuth await/quiescence가 흡수) 반환. (리뷰 I6: 상태코드 202로 고정.)
- 검증 대상 핵심 SQL은 **C의 `ledger_entries` insert**(Tram 경계 너머 실행).

## 4. 데이터 설계 (리뷰 Sonnet I8 / GPT I3)

- **단일 PostgreSQL 인스턴스, 서비스별 분리 DB**: `orderdb`(A), `reservationdb`(B), `ledgerdb`(C).
  Eventuate outbox 패턴상 **`message` 테이블은 발행 서비스의 비즈니스 DB와 같은 DB**(같은 트랜잭션 경계)에
  있어야 하므로 B의 `reservationdb`에 `message` 테이블, C의 `ledgerdb`에 `received_messages`(중복제거) 테이블.
- **테이블(DDL, init SQL로 생성)**:
  - `orderdb.orders(id PK, user_id, amount, created_at)`
  - `reservationdb.reservations(id PK, order_id, user_id, amount, created_at)` + Eventuate `message`/`message_*`.
  - `ledgerdb.ledger_entries(id PK, order_id, user_id, amount, created_at)` + Eventuate `received_messages`.
- **트랜잭션 경계**: B의 `reservations` insert + `OrderReserved` 이벤트 발행(=`message` insert)은 **같은
  Hibernate 트랜잭션**(Eventuate Tram의 트랜잭셔널 아웃박스). 따라서 둘 다 **요청 trace-id가 박힌** 같은 스레드.
- **C 멱등성**: Tram이 `received_messages`로 메시지 중복 처리를 방지(at-least-once 대비). `ledger_entries`는
  `order_id` unique로 추가 가드(선택).
- **빌더 `--jdbc-url`**: dialect 감지용으로 `jdbc:postgresql://localhost:<port>/orderdb`(A) 1개면 충분.
  B/C는 각자 `SPRING_DATASOURCE_URL`로 자기 DB 연결.

## 5. 컴포넌트 (docker-compose, `samples/legacy-tram/`)

인프라:
- `kafka`(+zookeeper 또는 KRaft 단일) — Eventuate CDC가 메시지를 publish하는 브로커.
- `postgres` — **WAL logical replication 활성**(`wal_level=logical`, `max_wal_senders`, `max_replication_slots`).
  3개 DB + Eventuate 테이블. CDC 전용 유저는 `REPLICATION` 속성 + 대상 테이블 접근 권한.
- `eventuate-cdc-service` — 공식 이미지(`eventuateio/eventuate-cdc-service`). **Postgres-WAL** 모드로 outbox
  `message` 테이블 변경을 tail → Kafka 릴레이. 필요한 env(구현 계획에서 확정, 공식
  `docker-compose-postgres-wal` 템플릿 기준): `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
  `EVENTUATELOCAL_KAFKA_BOOTSTRAP_SERVERS`, `EVENTUATE_CDC_TYPE`, `SPRING_PROFILES_ACTIVE=postgres-wal`,
  reader/leadership 설정.
- **기동 순서(리뷰 Sonnet I2 — 치명)**: `eventuate-cdc-service.depends_on = [postgres(healthy), kafka(healthy)]`,
  그리고 **`ledger(C).depends_on = [eventuate-cdc-service(started), kafka, postgres]`**. 이렇게 해야 빌더의
  `docker compose up -d --wait <capture-services>`(= order-web,reservation,ledger)가 의존성 그래프로 **CDC를
  끌어올린다**. CDC가 안 뜨면 B의 메시지가 C에 전달 안 돼 R1이 "전파 실패"가 아닌 이유로 거짓이 되므로 필수.

애플리케이션(각자 자체 Gradle Java8 toolchain + Dockerfile, **메인 Gradle 멀티빌드 미포함**):
- `order-web`(A) / `reservation`(B) / `ledger`(C): **Java 8 + Spring Boot 2.7.x + Hibernate 5 +
  Spring Cloud Sleuth(Brave/B3) + Eventuate Tram**.
- **로그 형식 요구(리뷰 Gemini I2)**: logback 패턴이 (1) **`%X{traceId}`** 를 포함하고(전제 R2 충족), (2) 로거명과
  메시지를 **표준 ` : ` 구분자**(Spring Boot 기본)로 분리해야 한다 — `SqlLogParser`가 prefix를 ` : ` 기준으로
  나누고 H5 `org.hibernate.SQL : ...` 형식을 매칭하기 때문.
- **설정 제약(리뷰 Sonnet I4)**: 빌더 override가 각 capture-service의 **`SPRING_APPLICATION_JSON`을 교체**하므로,
  샘플 서비스는 datasource/kafka 등 앱 설정을 **개별 환경변수**(`SPRING_DATASOURCE_URL`,
  `SPRING_KAFKA_BOOTSTRAP_SERVERS` 등)로 주되 `SPRING_APPLICATION_JSON`에 의존하지 말 것(override가 덮어씀).
- **버전 핀(잠정, 리뷰 Gemini I4/Sonnet I6/GPT I5 — 구현 계획에서 검증·확정)**: Spring Boot 2.7.x, Spring
  Cloud(Sleuth 호환 train), Eventuate Tram core + `eventuate-tram-spring-cloud-sleuth-integration` 동일 버전,
  `eventuate-cdc-service` 호환 태그. Java 8 toolchain.

## 6. trace 전파 경로 (R1 메커니즘, 리뷰 I3/I5)

```
builder ──B3 멀티헤더 주입──▶ POST /orders (A)        (B3 = X-B3-TraceId/SpanId/Sampled + b3; Spec 2 §7)
  A: Brave HTTP 서버가 B3 수용 → trace 컨텍스트 설정
  A ──동기 HTTP(B3 전파)──▶ POST /reservations (B)
  B: Brave HTTP 서버가 B3 수용 → 그 trace 컨텍스트에서 reservations insert + Tram 이벤트 발행(같은 TX)
     └▶ Eventuate↔Brave 통합이 trace 헤더를 message 행에 기록
  CDC: WAL tail → Kafka publish (메시지의 trace 헤더 보존)
  C: Tram 구독자가 Kafka에서 수신 → Eventuate가 trace 컨텍스트 복원 → 컨슈머 스레드 MDC traceId
     └▶ C logback이 H5 SQL/bind 라인에 동일 trace-id 출력  ◀── R1이 검증하는 지점
```

- **상관 헤더는 B3**(Spec 2 §7: sleuth 모드는 B3 멀티헤더 주입, traceparent 미사용). 빌더의 `SleuthLogCapture`가
  요청별 유니크 B3 traceId를 발급·주입하고, 그 traceId가 박힌 로그 라인만 상관한다.
- **1순위 통합(리뷰 I3 — artifact 확인됨)**: 각 서비스에
  **`io.eventuate.tram.core:eventuate-tram-spring-cloud-sleuth-integration`** 의존성 추가가 B→C trace 전파의
  1순위 배선. (Maven Central 게시·유지보수 확인.)
- **폴백(통합 jar 버전 비호환 시) — 커스텀 Tram `MessageInterceptor`**:
  - 발행측(`preSend`/`beforeSend`): `brave.Tracing.currentTracer().currentSpan().context()`에서 B3를 추출해
    메시지 헤더(`X-B3-TraceId`/`X-B3-SpanId`/`X-B3-Sampled`)로 복사.
  - 수신측(`preHandle`/`beforeHandle`): 그 헤더를 Brave `Propagation.extractor`로 추출 →
    `tracer.nextSpan(extracted)` 생성 → try-with-resources `SpanInScope`로 컨슈머 스레드에 활성화(MDC 반영).
- **최대 노력 기준 / R1-거짓 판정**: 1순위 통합 + (필요 시) 폴백 인터셉터까지 시도한 뒤에도 C 로그에 동일 trace-id가
  안 나오면 **R1=거짓**으로 판정·문서화하고 sleuth probe/window 대체 경로 설계를 후속으로 트리거.

## 7. builder attach 호출 (런북 내부)

> **사전조건**: PR #60 빌더(`--trace-mode`/`--capture-services` 지원). 런북이 fail-fast 점검(§2).

```
graph-rag-builder build --attach \
  --sut-compose samples/legacy-tram/docker-compose.yml \
  --app-service order-web --app-container-port 8080 --app-port <hostA> --jacoco-port <hostJ> \
  --jdbc-url jdbc:postgresql://localhost:<hostDb>/orderdb \
  --kafka-bootstrap localhost:<hostKafka> \
  --trace-mode sleuth --capture-services order-web,reservation,ledger \
  --sut-src <A 소스> --sut-jar <A bootjar> --out <out>
```

- 빌더가 A 엔드포인트를 인덱싱하고 `POST /orders`에 요청(B3는 SleuthLogCapture가 주입).
- `--capture-services`가 a/b/c 컨테이너 로그를 한 파일로 인터리브 tail(빌더가 appService 자동 포함).
- jacoco 커버리지는 A만(override가 app 서비스에 jacoco agent 주입). C의 비동기 SQL은 trace-id 상관으로 회수.

## 8. E2E 수용 기준 (런북 `e2e/run-legacy-tram-sleuth-e2e.sh`)

double-loop 바깥(수용 = 이 라이브 런북, out-of-process 블랙박스, 프로젝트 최고 가능 레벨). 런북: 빌더 sleuth
지원 점검 → 스택 up(healthcheck/대기) → 검증 3종 → down. **DoD = 3종 전부 PASS + 실행법 문서화.**

1. **R1 (trace-id 전파, 빌더 독립)**: 알려진 B3 trace-id로 `POST /orders` 1건 curl. **요청 직전 C 로그 offset/marker
   기록**, curl 후 **최대 30s, 250ms 간격 폴링**으로 C(ledger) 컨테이너 로그에서 그 trace-id가 박힌 H5
   `org.hibernate.SQL`/bind(`ledger_entries`) 라인을 찾는다. PASS=30s 내 동일 trace-id로 출현. FAIL=타임아웃 +
   A/B/C/CDC/Kafka 로그·상태 덤프. (리뷰 Sonnet I5/GPT I4: 비동기 CDC 지연 대비 명시적 대기 예산.)
2. **builder 캡처**: §7대로 attach 실행 → 출력 `graph.json`의 `POST /orders` 경로에 **적어도** A `orders` insert +
   B `reservations` insert + **B의 Eventuate `message`(outbox) insert(같은 TX·동일 trace-id이므로 정상 귀속,
   노이즈 아님 — 리뷰 Sonnet I7)** + C `ledger_entries` insert가 그 요청에 귀속(정확히 N개가 아니라 **최소 집합**으로 단언).
3. **인프라 노이즈 배제**: Eventuate CDC가 **백그라운드 스레드**에서 상시 폴링/리더십 갱신하는 SQL(요청 trace-id
   없음)은 캡처 graph에 **미포함**. (B의 outbox insert는 요청 TX라 trace-id가 있어 #2에 포함되는 것과 구분.)

각 검증은 PASS/FAIL을 명확히 출력, 하나라도 FAIL이면 런북 비-0 종료 + 진단 덤프.

## 9. 테스트 / DoD

- **바깥(수용)**: §8의 라이브 런북 3종 PASS.
- **안쪽**: 샘플 서비스 자체 단위테스트는 최소(샘플=테스트 픽스처). 각 서비스 부팅·핵심 핸들러 동작 smoke 수준만(선택).
- CI 미포함(Docker+Kafka+CDC 무게·플래키) — 로컬 런북이 수용 게이트. README/런북에 사전조건(Docker, PR #60
  빌더)과 실행법 명시.

## 10. 리스크 / 미해결

- **R1 미전파(치명)**: §6의 1순위 통합 + 폴백 인터셉터로도 안 되면 = Spec 2 R1 가정이 깨진 것 → 샘플이 "R1 거짓"을
  명확히 문서화, sleuth probe/window 대체 경로 설계를 별도 후속으로 트리거. (샘플의 가치는 진위를 드러내는 것.)
- **버전 정합성**: Eventuate Tram ↔ Boot2.7 ↔ Java8 ↔ sleuth-integration ↔ cdc 이미지. 비호환 시 부팅 실패 →
  구현 계획에서 공식 `docker-compose-postgres-wal` 템플릿 기준 검증 조합으로 핀 고정.
- **기동 순서/플래키**: CDC는 Postgres WAL + Kafka ready 후 시작(§5 depends_on + healthcheck). `wal_level=logical`,
  replication slot, CDC 유저 권한 누락 시 CDC 부팅 실패 — init SQL/compose에 명시.
- **로그 파싱 성능(리뷰 Gemini I3)**: 멀티서비스(a/b/c + 인프라) 인터리브 로그가 빠르게 커지면 `SleuthLogCapture`의
  매 폴링 전체 재스캔이 O(n²)로 탐색을 느리게 할 수 있다. 본 게이트(단일/소수 요청)에는 충분하나, 대량 요청 시
  델타-커서 증분 파싱이 필요할 수 있음 — 빌더 후속 최적화로 추적(본 spec 범위 밖).
- **인코딩**: 샘플 로그/DB UTF-8. 한글·euc-kr은 직교(별개 후속).

## 11. Out of Scope

CI 통합, 빌더 MySQL 스키마 지원, probe/window 대체 경로 구현, 한글/euc-kr 데이터 단계, 샘플 광범위 비즈니스
로직·단위테스트, 메인 Gradle 멀티빌드 통합, `SleuthLogCapture` 델타-커서 최적화.

## 12. 3-Model 리뷰 반영 기록 (2026-06-18)

3-model 교차 리뷰(Claude Sonnet + Gemini 3.5 Flash High + GPT-5.5) 판정·반영:

- **수용(critical)**: §6 빌더 명령이 main 빌더에 없는 `--trace-mode`/`--capture-services` 사용(전 리뷰어 I1)
  → §2에 빌더 의존성·런북 fail-fast 점검 명시. `eventuate-cdc-service` 기동 누락(Sonnet I2) → §5 depends_on
  체인으로 CDC를 up 그래프에 포함. `SchemaExtractor` MySQL 미지원(Gemini I1) → **DB를 PostgreSQL로 전환**
  (Postgres-WAL CDC, 빌더 검증 경로; MySQL 지원은 범위 밖 추적).
- **수용(important)**: Eventuate↔Sleuth 통합 모호(Sonnet I3/GPT I5) → 1순위 artifact
  `eventuate-tram-spring-cloud-sleuth-integration` 명시 + 폴백 MessageInterceptor 의사계약 + R1-거짓 판정 기준.
  CDC env/Postgres WAL·권한 미명시(Sonnet I6) → §5 보강. R1 직접검증 대기 예산 부재(Sonnet I5/GPT I4) → §8
  30s/250ms 폴링. 데이터 설계 부재(Sonnet I8/GPT I3) → §4 신설(분리 DB·outbox 같은 TX·멱등). B outbox SQL이
  footprint에 포함(Sonnet I7) → §8 #2를 "최소 집합 + outbox insert 정상 귀속"으로. SPRING_APPLICATION_JSON
  교체(Sonnet I4) → §5 설정 제약. logback ` : ` 구분자(Gemini I2) → §5 로그 형식 요구. B3 vs traceparent
  명확화(GPT I2) → §6에 Spec 2 §7 B3 계약 참조. 상태코드(GPT I6) → §3 202 고정.
- **수용(recommended/noted)**: 버전 핀(Gemini I4 등) → §5 잠정 매트릭스(계획에서 확정). 로그 파싱 O(n²)
  (Gemini I3) → §10 리스크(범위 밖 빌더 후속).
- **반려/조정**: Gemini I1의 "빌더 MySQL 수정"은 채택하지 않고 **DB를 Postgres로 전환**해 우회(빌더 변경은 Spec
  2/빌더 범위, 본 spec은 샘플+E2E에 집중). 그 외 outright 반려 없음.
