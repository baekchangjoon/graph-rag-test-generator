# 레거시 Eventuate Tram 샘플 스택 + 라이브 E2E 설계 (Spec 1)

- 작성일: 2026-06-18
- 상태: Draft (설계 승인 → 3-model 리뷰 → 사용자 검토 대기)
- 관련: Spec 2 = sleuth trace-mode 구현(PR #60, 브랜치 `worktree-poc-legacy-async-sql-capture`).
  본 spec은 그 **최종 수용 게이트**.
- 브랜치: `worktree-legacy-tram-sample` (main 기준).

## 1. 목적 / 수용 게이트

이 샘플의 유일한 존재 이유는 **Spec 2(sleuth trace-mode)의 라이브 수용 게이트**다. 합성 픽스처 단위/통합으로만
검증한 sleuth 경로를 충실한 레거시 스택에서 실증한다. 핵심은 **R1**:

> A에 주입한 B3 trace-id가 **동기 HTTP(A→B) + 비동기 Tram(B→C, Eventuate CDC 경유)** 경계를 **동일 값으로**
> 넘어 C 컨슈머 스레드 MDC까지 전파되어, C의 Hibernate 5 SQL 로그 라인에 같은 trace-id로 찍히는가?

R1이 참이면 sleuth가 비동기 cross-service SQL을 요청 단위로 회수할 수 있음이 라이브로 확정된다. R1이 거짓이면
(전파 안 됨) 그 자체가 valid finding — sleuth의 R1 가정이 깨진 것이므로 probe/payload 상관(백업)으로 회귀할
근거가 된다. **이 샘플은 R1의 진위를 드러내는 것이 1차 가치다.**

## 2. 범위와 분해

- **이 spec (Spec 1)**: `samples/legacy-tram/` 자급 docker-compose 스택(3 Boot2/Java8 서비스 + Kafka +
  MySQL + Eventuate CDC 서비스) + 라이브 E2E 런북(`e2e/run-legacy-tram-sleuth-e2e.sh`).
- **의존성**: E2E 런북은 builder를 `--trace-mode sleuth` 로 실행하므로 **Spec 2(PR #60)가 머지된 빌더**(또는
  그 브랜치에서 빌드한 빌더)가 필요하다. 샘플 스택 자체(SUT)는 빌더와 독립이라 PR #60 없이도 빌드/기동된다.
- **Out of scope**: 빌더 코드 변경(Spec 2에서 완료), CI 통합(로컬 런북만), 샘플의 광범위한 비즈니스 로직
  (R1 검증에 필요한 최소 도메인만), MySQL 외 DB, probe/window 대체 경로 구현(R1 실패 시 별도 후속).

## 3. 도메인 흐름 (각 홉이 SQL을 낸다)

최소이되 세 서비스가 각각 SQL을 내고, 한 개의 동기 HTTP 홉 + 한 개의 비동기 Tram 홉을 거친다.

| 서비스 | 역할 | SQL | 다음 홉 |
|---|---|---|---|
| **A `order-web`** | HTTP entry. `POST /orders {userId, amount}` | `orders` insert (H5@A) | 동기 HTTP `POST /reservations` |
| **B `reservation`** | `POST /reservations {orderId,userId,amount}` | `reservations` insert (H5@B) | Eventuate Tram 도메인 이벤트 `OrderReserved` 발행 (outbox 기록) |
| **C `ledger`** | `@EventHandler(OrderReserved)` (Tram 구독) | `ledger_entries` insert (**타깃 비동기 H5@C**) | — |

- A는 `orders` insert 후 B를 동기 호출하고 202(또는 201) 반환. **응답 시점엔 C SQL 미완**(비동기) — sleuth의
  await/quiescence가 이를 흡수.
- 검증 대상 핵심 SQL은 **C의 `ledger_entries` insert**(Tram 경계 너머 실행).

## 4. 컴포넌트 (docker-compose, `samples/legacy-tram/`)

인프라:
- `zookeeper` + `kafka` (또는 KRaft 단일 kafka) — Eventuate CDC가 메시지를 publish하는 브로커.
- `mysql` — **binlog 활성**(Eventuate CDC가 트랜잭션 로그 tail). A/B/C 스키마 + Eventuate `message`/
  `received_messages` 테이블. collation은 utf8mb4(한글/euc-kr 데이터는 별개 단계, PoC 기록 참조).
- `eventuate-cdc-service` — 공식 이미지(`eventuateio/eventuate-cdc-service`). MySQL binlog → Kafka 릴레이.

애플리케이션(각자 자체 Gradle Java8 toolchain + Dockerfile, **메인 Gradle 멀티빌드 미포함**):
- `order-web`(A) / `reservation`(B) / `ledger`(C): **Java 8 + Spring Boot 2.x + Hibernate 5 +
  Spring Cloud Sleuth(Brave/B3) + Eventuate Tram**.
- 각 서비스 logback 패턴에 **`%X{traceId}`** 포함 — Spec 2 전제 R2(SUT logback이 trace-id 출력)를 샘플이
  충족한다. (커스텀 logback이라 `logging.pattern.*` 주입은 무시되므로 샘플 이미지에 내장.)
- 버전 핀 고정: Eventuate Tram ↔ Boot 2.x ↔ Java 8 ↔ `eventuate-cdc-service` 이미지 태그를 호환 조합으로
  고정(구현 계획에서 구체 버전 확정).

## 5. trace 전파 경로 (R1 메커니즘)

```
builder ──B3 헤더 주입──▶ POST /orders (A)
  A: Brave HTTP 서버 instrumentation이 B3 수용 → trace 컨텍스트 설정
  A ──동기 HTTP(B3 전파)──▶ POST /reservations (B)
  B: Brave HTTP 서버가 B3 수용 → 그 trace 컨텍스트에서 Tram 이벤트 발행
     └▶ Eventuate↔Brave 통합이 trace 헤더를 메시지에 기록 → MySQL message 테이블
  CDC: binlog tail → Kafka publish (메시지의 trace 헤더 보존)
  C: Tram 구독자가 Kafka에서 수신 → Eventuate가 trace 컨텍스트 복원 → 컨슈머 스레드 MDC traceId
     └▶ C logback이 H5 SQL/bind 라인에 동일 trace-id 출력  ◀── R1이 검증하는 지점
```

**핵심 불확실성**: B의 Tram 발행 → C의 수신 사이 trace 컨텍스트 전파는 Eventuate↔Sleuth 통합 배선에 달려
있다. out-of-box로 안 되면 `eventuate-tram-spring-cloud-sleuth`(있으면) 또는 **커스텀 Tram
MessageInterceptor**(발행 시 현재 B3를 메시지 헤더로 복사, 수신 시 컨슈머 스레드 trace 컨텍스트로 복원)를
샘플에 배선한다. 이 배선을 찾아 확정하는 것이 샘플 구현의 핵심 난제다.

## 6. builder attach 호출 (런북 내부)

```
graph-rag-builder build --attach \
  --sut-compose samples/legacy-tram/docker-compose.yml \
  --app-service order-web --app-container-port 8080 --app-port <hostA> --jacoco-port <hostJ> \
  --jdbc-url jdbc:mysql://localhost:<hostDb>/orderdb \
  --kafka-bootstrap localhost:<hostKafka> \
  --trace-mode sleuth --capture-services order-web,reservation,ledger \
  --sut-src <A 소스> --sut-jar <A bootjar> --out <out>
```

- 빌더는 A의 엔드포인트를 인덱싱하고 `POST /orders`에 요청을 보낸다(B3는 SleuthLogCapture가 주입).
- `--capture-services` 가 a/b/c 컨테이너 로그를 한 파일로 인터리브 tail(builder가 appService를 자동 포함).
- jacoco 커버리지는 A만(override가 app 서비스에 jacoco agent 주입). C의 비동기 SQL은 trace-id 상관으로 회수.

## 7. E2E 수용 기준 (런북 `e2e/run-legacy-tram-sleuth-e2e.sh`)

double-loop 바깥(수용 = 이 라이브 런북, out-of-process 블랙박스, 프로젝트에서 최고 가능 레벨). 런북은
스택 up → 검증 3종 → down. **DoD = 아래 3종 전부 PASS + 실행법 문서화.**

1. **R1 (trace-id 전파)**: 알려진 B3 trace-id로 `POST /orders` 1건 전송 → **C(ledger) 컨테이너 로그의
   H5 SQL/bind 라인에 그 trace-id가 동일 값으로 출현**. (직접 curl + 로그 grep으로 빌더와 독립 검증.)
2. **builder 캡처**: 빌더를 §6대로 attach 실행 → 출력 `graph.json`의 `POST /orders` 엔드포인트 경로에
   **C의 `ledger_entries` insert + B의 `reservations` insert + A의 `orders` insert**가 그 요청에 귀속.
3. **인프라 노이즈 배제**: Eventuate CDC/relay가 상시 폴링하는 `message`/`received_messages` 등 배경 SQL
   (요청 trace-id 없음)은 캡처 graph에 **미포함**.

각 검증은 PASS/FAIL을 명확히 출력하고, 하나라도 FAIL이면 런북이 non-zero exit + 진단 덤프(관련 컨테이너
로그 슬라이스)를 남긴다.

## 8. 테스트 / DoD

- **바깥(수용)**: §7의 라이브 런북 3종 PASS.
- **안쪽**: 샘플 서비스 자체 단위테스트는 최소(샘플은 테스트 픽스처 — 광범위 단위테스트 비대상). 단, 각 서비스가
  부팅·핵심 핸들러가 동작함을 보장하는 smoke 수준은 둔다(선택).
- CI 미포함(Docker+Kafka+CDC 무게·플래키) — 로컬 런북이 수용 게이트. README/런북에 사전조건(Docker, 빌더가
  sleuth 지원 = PR #60 머지)과 실행법 명시.

## 9. 리스크 / 미해결

- **R1 미전파(치명)**: Eventuate Tram trace 전파가 통합 배선으로도 안 되면 = Spec 2의 R1 가정이 깨진 것.
  이 경우 샘플은 "R1 거짓"을 명확히 문서화하고, sleuth의 probe/window 대체 경로 설계를 별도 후속으로 트리거.
  (샘플의 가치는 진위를 드러내는 것.)
- **버전 정합성**: Eventuate Tram ↔ Boot2 ↔ Java8 ↔ eventuate-cdc-service 이미지. 비호환 시 부팅 실패 →
  구현 계획에서 검증된 조합으로 핀 고정.
- **기동 순서/플래키**: CDC는 Kafka + MySQL binlog ready 후 시작해야 함. 런북이 healthcheck/대기(depends_on
  + 폴링)로 순서 보장. binlog 활성(`--log-bin`, `binlog_format=ROW`)을 MySQL 컨테이너에 설정.
- **인코딩**: 샘플 로그/DB UTF-8. 한글 데이터·euc-kr collation은 R1/캡처 검증과 직교(별개 후속).
- **빌더 의존**: PR #60 미머지 시 런북은 sleuth 미지원 빌더로 실패 → 런북이 빌더의 sleuth 지원을 사전 점검하고
  안내.

## 10. Out of Scope

CI 통합, MySQL 외 DB, probe/window 대체 경로 구현, 한글/euc-kr 데이터 단계, 샘플의 광범위 비즈니스 로직·
단위테스트, 메인 Gradle 멀티빌드 통합.
