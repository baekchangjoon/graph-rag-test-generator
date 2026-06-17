# OTEL Agent 기반 SQL 캡처 — 설계 (spec)

- 작성일: 2026-06-18
- 상위 로드맵: [docs/27 — 항목 1](../../27-roadmap-otel-capture-stub-seeding.md)
- 관련 문서: [docs/06 테스트 환경(OTEL propagation/isolation)](../../06-test-environment.md), [docs/24 exploration backends & InputOracle](../../24-exploration-backends-and-input-oracle.md), [docs/26 attach mode](../../26-attach-mode.md)

---

## 1. 문제

현재 SQL/바인딩 캡처는 SUT가 stdout에 남기는 **Hibernate/MyBatis 로그 파싱**(`SqlLogParser`)이고,
요청 귀속은 **로그 byte-offset 구간**(`SutHandle.logOffset()`/`readLogRange()`)으로 한다. 한계:

- **동시성 취약** — byte-offset 창은 단일 직렬 실행을 전제한다. 병렬/비동기 작업이 끼면 구간이 섞인다.
  특히 Kafka consumer(비동기)는 발행→소비 사이 다른 SQL이 끼면 오귀속된다.
- **로그 접근 의존** — attach 모드는 컨테이너 로그를 `docker compose logs` 스트림으로 끌어와야만 SQL을
  본다. 로그 포맷/버퍼링에 민감하다.
- **ORM 결합** — Hibernate/MyBatis 로그 형식별 파서가 필요하다.

## 2. 목표 / 비목표

### 목표
- SQL 캡처를 **교체 가능한 backend 인터페이스**(`SqlCaptureBackend`) 뒤로 추상화한다.
- **`OtelSpanCapture`(1순위)** — 빌더가 in-process OTLP 리시버를 띄우고, SUT의 OTEL Java agent
  v2.16.0이 내보내는 DB span(SQL + bind 값)을 수신해 **trace-id로 요청에 정확히 귀속**한다.
- **`LogParserCapture`(폴백)** — 기존 로그 파서 경로를 인터페이스 뒤로 이동. OTEL 실패/미수집
  케이스의 폴백.
- 검증(PoC) 후 기본값을 OTEL로 전환한다.
- 적용 경로: **HTTP 엔드포인트 탐색(`EndpointExplorationRunner`) + Kafka(`KafkaCaptureRunner`)**.
- analysis 모드 + **attach 모드** 둘 다.

### 비목표 (이번 사이클 제외)
- **WS/STOMP(`WsCaptureRunner`)** — STOMP/WebSocket은 OTEL의 header 기반 context 추출 표준화가
  약해 trace-id 상관이 불확실. 이번엔 log-parser 경로 유지. (필요 시 별도 PoC + 사이클.)
- 로드맵 항목 2(OpenAPI stub seeding), 항목 3(JTO/SAJ override 경고) — 별도 사이클.
- HTTP 외부 교환 캡처(`HttpCaptureServer.drainNewExchanges`) 및 생성 테스트의 baggage 매칭 —
  **무변경**. SQL 귀속만 trace-id로 옮기고, 기존 `baggage: test-id=explore` 헤더와 HTTP capture
  경로는 그대로 둔다(항목 2와의 결합 회피).

## 3. 핵심 결정 (브레인스토밍 합의)

| 결정 | 내용 | 근거 |
|---|---|---|
| 범위 | 풀 구현(인터페이스 + OTEL backend + 리시버 + 귀속 + 기본전환), analysis+attach | 사용자 합의 |
| 귀속 | **trace-id 상관** — 빌더가 요청별 고유 trace 생성 → W3C `traceparent`를 outbound 주입 | 커스텀 baggage 키 의존 제거, 표준 전파만 |
| 결정성 | **entry span 완료 await + 짧은 quiescence + timeout** (고정 sleep 제거) | span은 OTLP로 비동기 도착 → flaky 회피 |
| transport | **OTLP HTTP/JSON** (agent `OTEL_EXPORTER_OTLP_PROTOCOL=http/json`) | 빌더는 작은 HTTP 서버 + Jackson 파싱. protobuf/gRPC 의존 불필요 |
| 컬럼 매핑 | **기존 SQL 텍스트 파싱 재사용** (`ParsedSql.columnForPosition` 등) | OTEL은 **bind 값 소스 + 귀속만** 대체 |
| batch 완화 | 분석 기동 시 `hibernate.jdbc.batch_size=0` 주입 | semconv상 batch 연산엔 파라미터 미수집 → batch 비활성으로 회피, 그래도 빈 경우 폴백 |
| PoC 게이트 | Phase 1을 게이트로 — 실패 시 해당 경로만 폴백 유지·기본전환 보류 | 로드맵 명시 |

## 4. 아키텍처

### 4.1 컴포넌트 (단위 경계)

```
                 begin() ─► Scope
SqlCaptureBackend ┤        ├ requestHeaders(): Map<String,String>   (주입할 상관 헤더)
                 │        └ drain(): List<ParsedSql>                (begin 이후 SQL+bindings, 순서 보존)
                 │
                 ├─ LogParserCapture  (폴백) ── SqlLogParser + SutHandle.readLogRange
                 └─ OtelSpanCapture   (1순위) ── OtlpTraceReceiver 조회 + trace-id 상관
```

- **`SqlCaptureBackend`** (인터페이스, `io.graphrag.builder.capture`)
  - `Scope begin()` — 요청 1건의 캡처 범위를 연다.
- **`Scope`** (인터페이스)
  - `Map<String,String> requestHeaders()` — 요청에 주입할 상관 헤더(OTEL: `traceparent` 1개,
    log-parser: 빈 맵). **transport-agnostic** — 호출자가 HTTP 헤더 또는 Kafka 레코드 헤더로 주입.
  - `List<ParsedSql> drain()` — begin 이후 SUT가 발행한 SQL을 순서 보존하여 반환. OTEL은 entry
    span 완료 await + quiescence 후 DB span 환원, log-parser는 `readLogRange` 파싱.

- **`LogParserCapture implements SqlCaptureBackend`**
  - `begin()` → `logStart = sut.logOffset()` 캡처한 `Scope` 반환. `requestHeaders()` = `Map.of()`.
  - `drain()` → `SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()))`.
  - 기존 `SqlLogParser`는 그대로 유지(이 backend가 위임).

- **`OtelSpanCapture implements SqlCaptureBackend`**
  - 생성자에 `OtlpTraceReceiver` + **`SutHandle`(폴백용 로그 접근)** + `runId` 주입.
  - `begin()` → traceId/spanId 생성 + **`logStart = sut.logOffset()` 동시 기록**(빈 캡처 시
    per-request log-parser 폴백용 — 리뷰 반영). `requestHeaders()` = `{"traceparent":
    "00-<traceId>-<spanId>-01"}`.
    - **traceId 생성(재현성 vs 유일성)**: `runId`(런당 1회 결정적 16-byte 시드) prefix + 요청별
      **단조 카운터**로 16-byte traceId, spanId는 카운터 파생 8-byte. `Math.random`/`new Date`
      미사용. 같은 runId·시드로 재실행 시 동일 traceId 시퀀스(재현성), 서로 다른 런은 runId가
      달라 충돌 없음(유일성). W3C 포맷 검증 단위 테스트.
  - `drain()` → 리시버에서 **"injected spanId를 부모로 가지는 entry span"** 도착까지 await
    + quiescence drain. 그 trace의 DB span(`db.system`/`db.query.text`/`db.query.parameter.*`)을
    span start 시각 순으로 `ParsedSql`로 환원. drain 후 해당 traceId를 **즉시 리시버에서 제거**.
    - `db.query.text`(placeholder `?`)는 그대로 `ParsedSql.sql`로 → 기존 컬럼 매핑 재사용.
    - **파라미터 인덱스 키 규약은 PoC(Phase 1)에서 실측 확정** — semconv/문헌이 0-based(로드맵
      인용)와 1-based(JDBC index)로 엇갈림. `ParsedSql.Binding`은 **1-based position 규약**이므로
      PoC 실측 결과에 맞춰 매핑(0-based면 +1, 1-based면 그대로). spec은 어느 쪽도 단정하지 않음.
    - **timeout/quiescence/폴백 기본값**: entry span await timeout 8s(Kafka `AWAIT_MILLIS`와 정렬),
      quiescence 무-신규-span 임계 250ms(`lastArrivalNanos` 기반), poll 50ms. 또한 에이전트
      `OTEL_BSP_SCHEDULE_DELAY`를 짧게(예: 100ms) 설정해 배치 export 지연을 quiescence 창보다
      작게 한다(false-quiescent 방지). **timeout 시**: `logStart` 기준 log-parser 폴백 1회 시도 후,
      그래도 비면 빈 리스트 + 경고 로그.

- **`OtlpTraceReceiver`** (신규 `io.graphrag.builder.capture.otlp`)
  - 동적 포트 HTTP 서버. `POST /v1/traces` 수신, `Content-Type: application/json`(OTLP/JSON
    `ExportTraceServiceRequest`)을 Jackson으로 디코드.
  - 누적: `Map<traceId, List<SpanRecord>>` (thread-safe). `spanId`, `parentSpanId`, `name`,
    `kind`, `startNanos`, `links`, attributes(특히 `db.*`) 보존.
  - API: `awaitEntrySpan(traceId, parentSpanId, timeout)`, `spans(traceId)`, quiescence용
    `lastArrivalNanos(traceId)`, **`remove(traceId)`(drain 후 eager cleanup)**.
  - **Kafka link 분기(조건부, Phase 1 결과 반영)**: PoC②에서 consumer span이 자식(같은 traceId)이
    아니라 **span link**(다른 traceId + injected traceId로의 link)로 판명되면, 리시버에 **링크
    역인덱스** `Map<injectedTraceId, List<SpanRecord>>`를 추가하고 `awaitEntrySpan`이 trace-id 직접
    매칭 **또는** link 보유 span을 함께 본다. 자식이면 이 인덱스 불필요. Phase 5 착수 시 이 섹션을
    Phase 1 결과로 확정.
  - 메모리: 정상/timeout 불문 `drain()`에서 `remove(traceId)` 호출(eager). 장시간 분석에서 누적
    방지. `OtlpTraceReceiver` 단위 테스트에 cleanup 검증 포함.
  - Environment가 소유(start/stop).

### 4.2 데이터 흐름 (HTTP 1요청)

```
EndpointExplorationRunner.doSend
  scope = sqlCapture.begin()                       # OTEL: traceId/spanId 발급
  HTTP 요청 빌드:
    + baggage: test-id=explore                     # (기존 유지 — HTTP capture/관찰용)
    + scope.requestHeaders() → traceparent          # (신규 — SQL 귀속용)
  http.send(...)                                    # SUT가 traceparent를 부모로 server span 생성
  coverage.dump(...) / CoverageFingerprint           # (기존)
  List<ParsedSql> sql = scope.drain()               # OTEL: entry span(parent=spanId) await + quiescence
  → captureSqlForRange의 후처리(컬럼 매핑 + API_PARAM/LITERAL 분류 + CapturedSql 조립) 재사용
```

**`InvocationOutcome` 리팩토링(비파괴적 — 리뷰 반영)**: `logStart`/`logEnd` 필드는 `PathCandidate`·
`ExplorationOrchestrator`·`WsCaptureRunner`(WS는 이번에 log-parser 유지)·Kafka 등 6개 사이트가
참조하므로 **제거하지 않는다**. 대신:
- `doSend`가 `scope.drain()` 결과(`List<ParsedSql>`)를 직접 후처리에 넘긴다. 즉 캡처의 진입점이
  "log 범위"에서 "backend가 drain한 `ParsedSql` 목록"으로 바뀐다.
- `captureSqlForRange(pathId, body, logStart, logEnd)` → `captureSql(pathId, body, List<ParsedSql>)`
  로 시그니처 변경(컬럼 매핑·origin 분류·CapturedSql 조립 로직은 동일, 입력 소스만 교체).
- `InvocationOutcome`의 `logStart`/`logEnd`는 **log-parser 경로 호환을 위해 보존**(OTEL 경로는
  0/0 또는 미사용). 향후 정리는 별도 — 이번엔 외과적 변경만.
- **`Thread.sleep(150)`(콘솔 flush 여유)은 `doSend`에서 제거**하고 `LogParserCapture.Scope.drain()`
  내부의 짧은 settle로 이동한다(OTEL 경로는 await가 대체하므로 sleep 불필요 — 리뷰 반영).

**상관 헤더 우선순위(리뷰 반영)**: `traceparent`는 OTEL backend가 **예약**한다. 사용자
`RequestHeaders`(`--request-headers-file`)가 동일 이름을 지정하면 빌더가 backend 값으로 **override
+ 경고 로그**(HTTP/Kafka 동일 정책). 기존 `baggage` 헤더는 그대로 유지.

### 4.3 데이터 흐름 (Kafka 1발행)

```
KafkaCaptureRunner.publishAndCapture
  scope = sqlCapture.begin()
  ProducerRecord 생성 + scope.requestHeaders()를 레코드 헤더로 주입(traceparent)
  producer.send(record).get()
  List<ParsedSql> sql = scope.drain()    # OTEL: consumer process span(parent/link=injected) await + quiescence
  → captureSql 후처리 재사용
```

기존 `awaitConsumerSql`(로그 폴링)은 OTEL 경로에서 `scope.drain()`의 span await로 대체. log-parser
경로에선 유지. **variant 발행(missing-field/duplicate — early-return으로 DB span 없음, 리뷰 반영)**:
OTEL 경로에서 full 8s timeout을 기다리면 탐색 시간이 늘어나므로, variant drain은 **단축 timeout**
(예: `VARIANT_SETTLE_MILLIS`=2.5s 정렬)로 호출한다. timeout 후 빈 SQL은 기대 동작(early-return arm).

### 4.4 통합 지점 (변경 파일)

- `gradle/libs.versions.toml` — `otelAgent` `2.14.0` → **`2.16.0`** (Phase 1 선행).
- `OtelAgent` env 확장 — **리시버 포트가 동적이라 `env(serviceName)` 호출 시점엔 URL을 모른다(리뷰
  반영)**. 따라서 시그니처를 **`env(String serviceName, String otlpEndpoint)`** 로 확장(또는 별도
  `otlpEnv(...)`)하고, 리시버가 start되어 포트가 확정된 **이후** `AnalysisEnvironment.start`/
  `runAttached`에서 호출/주입한다. OTEL 모드 env: `OTEL_TRACES_EXPORTER=otlp`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/json`, `OTEL_EXPORTER_OTLP_ENDPOINT=<확정된 리시버 URL>`,
  `OTEL_BSP_SCHEDULE_DELAY`(짧게), JDBC `capture-query-parameters` 활성
  (`OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS=true`).
  log-parser 모드: 기존 `none` 유지(baggage 전파만).
- `EndpointExplorationRunner` — `doSend`에 `scope` 배선(위 4.2). 150ms sleep 제거.
- `KafkaCaptureRunner` — `publishAndCapture`에 `scope` 배선(위 4.3). 레코드 헤더 주입.
- **batch 완화 — Spring JSON 단일 병합(리뷰 반영)**: `hibernate.jdbc.batch_size=0`을 기존 logging
  JSON과 **같은 `SPRING_APPLICATION_JSON` object에 병합**한다(별도 env로 넣으면 logging JSON을
  치환해 SQL 로그 폴백과 충돌). **analysis(`SutProcess.loggingJson`)와 attach
  (`OverrideComposeGenerator`) 양쪽 모두** 적용 — Spring JSON 추가 속성을 한 곳에서 merge하도록
  `SutOptions`/override `Spec`에 추가 속성 필드를 둔다.
- `BuilderCli` + `BuildConfig` — **`--sql-capture otel|log` 플래그 + `BuildConfig` 필드 신설**
  (현재 없음, 리뷰 반영).
- `AnalysisEnvironment` — `OtlpTraceReceiver` start → URL 확정 → `OtelAgent` env 주입 → 선택된
  `SqlCaptureBackend` 배선.
- `AttachedComposeEnvironment` + `OverrideComposeGenerator` — 호스트 리시버 기동 + app 서비스에
  OTEL env 주입. 엔드포인트는 컨테이너→호스트 도달용으로 **`http://host.docker.internal:<port>`**
  (Mac/Win), Linux는 override에 `extra_hosts: ["host.docker.internal:host-gateway"]` 주입
  (**Docker Engine 20.10+ 필요** — 미만 환경 감지 시 경고). backend는 동일 `OtelSpanCapture` 재사용.

### CLI 계약 (`--sql-capture`)
- **Phase 2–6**: 기본 `log`, `otel`은 opt-in(`--sql-capture otel`).
- **Phase 7**: PoC 게이트 + 수용 테스트 green 확인 후 기본을 `otel`로 전환.
- 명시 값(`--sql-capture …`)은 단계와 무관하게 **항상 우선**.

## 5. 리스크 / 오픈 질문

1. **드라이버별 bind 값 노출** — `capture-query-parameters`가 실제 `db.query.parameter.*`를
   내보내는지 + **파라미터 키 인덱스 규약(0-based vs 1-based)** 실측. 빌더 지원 dialect는
   **Postgres/MySQL/MariaDB**(`DbConfig.Type`; H2 미지원 — petclinic은 MySQL Testcontainer로 분석).
   PoC는 e2e가 쓰는 dialect로 검증하고, 가능하면 Postgres·MySQL 둘 다 확인. → **Phase 1 PoC 게이트①**.
2. **Kafka context 전파 방식** — consumer process span이 주입 context의 **자식(같은 trace-id)**
   인지 **span link(다른 trace-id + 우리 trace 링크)** 인지가 agent 설정/버전에 따라 갈림.
   - 자식이면 trace-id 동등 매칭으로 끝.
   - link면 매칭 규칙을 **trace-id OR "injected trace로의 link 보유"** 로 확장.
   - 둘 다 불가하면 **Kafka만 log-parser 폴백 유지**.
   → **Phase 1 PoC 게이트②** (tainted-spring MSA의 Kafka consumer SUT로 검증).
3. **batch 미수집** — `batch_size=0`로 회피하되, 그래도 빈 SQL이면 해당 요청만 log-parser 폴백.
4. **async 결정성** — entry span 완료 신호 + quiescence 창 크기 튜닝. timeout 시 폴백 또는 경고.
5. **attach 네트워킹** — Linux `host-gateway`는 Docker 20.10+ 필요. 미지원 환경 감지 시 경고.
   수용-4(docker e2e)는 Linux CI에서 `extra_hosts: host-gateway` 경로로 검증하므로 CI가 Docker
   20.10+인지 Phase 6 착수 전 확인(아니면 컨테이너 네트워크 내 리시버 주소 주입으로 대체).
6. **리시버 동시성·메모리** — 동일 SUT에 여러 trace 동시 흐름(향후 병렬 탐색)은 traceId 키로 격리.
   메모리 누적은 **`drain()` 시 eager `remove(traceId)`** 로 해소(4.1) — 정상·timeout 불문 정리,
   단위 테스트로 검증. (리스크 1·2와 달리 해결책 확정.)

## 6. E2E / 수용 테스트 (정의된 완료 조건)

CLAUDE.md 의무: 아래 수용 테스트는 **구현 전에 먼저 작성**(outer loop, red)하고, 단위 TDD로
구현을 구동한다. **완료 = 아래 전부 green.**

- **수용-1 (parity, analysis/HTTP)** — petclinic을 OTEL backend로 분석 → 기존 e2e 45개 그린 유지 +
  생성 테스트의 SQL bindings가 log-parser 산출과 **동등**(컬럼/값/순서).
- **수용-2 (귀속·동시성)** — 의도적으로 인터리브한 2요청(또는 비동기 작업 끼임) 시나리오에서 각
  요청의 SQL이 trace-id로 **정확히 분리**된다(byte-offset 경로가 섞이던 케이스). OTEL의 존재 이유.
- **수용-3 (Kafka)** — Kafka consumer SUT(tainted-spring) 발행 → consumer가 만든 SQL이 trace-id로
  귀속되어 캡처된다(레코드 헤더 traceparent 주입 경로).
- **수용-4 (attach)** — 컨테이너 SUT → 호스트 리시버 경로로 SQL 캡처(Testcontainers docker e2e).
- **PoC 게이트 (Phase 1, 위 리스크 1·2)** — 별도 spike 테스트/런으로 ①HTTP bind 값 노출,
  ②Kafka trace 상관 방식 확인. 실패 시 해당 경로 폴백·기본전환 보류.

### 내부 루프 단위 테스트 (대표)
- `OtlpTraceReceiver` — OTLP/JSON `ExportTraceServiceRequest` 디코드(중첩 resource/scope/span,
  `db.query.parameter.*` 속성 추출), traceId 누적, `awaitEntrySpan`/quiescence 판정, **`remove`
  eager cleanup**(drain 후 trace 잔류 없음).
- `OtelSpanCapture` — fake 리시버로 trace-id 상관, entry span(parent=spanId) await, DB span →
  `ParsedSql` 환원(parameter index 규약은 PoC 확정값 적용, 순서 보존), timeout 시 log-parser 폴백.
- `traceparent` 생성 — W3C 포맷(`00-<32hex>-<16hex>-01`), 결정적 생성.
- `LogParserCapture` — 기존 `SqlLogParser` 위임이 byte-offset 동작과 동일함을 회귀로 고정.

## 7. 구현 단계 개요 (plan에서 상세화)

1. **Phase 1 — PoC 게이트**: `otelAgent` 2.16.0 bump + petclinic HTTP `db.query.parameter.N` 노출
   확인 + Kafka consumer trace 상관 방식 확인. **go/no-go.**
2. **Phase 2 — backend 인터페이스 + log-parser 이동**: `SqlCaptureBackend`/`Scope` 도입,
   `LogParserCapture`로 기존 경로 이동(동작 동일, 회귀 green). 기본값 `log`.
3. **Phase 3 — OTLP 리시버 + OtelSpanCapture**: 리시버, 상관/await, ParsedSql 환원(단위 TDD).
4. **Phase 4 — HTTP 배선 + 수용-1/2**: `EndpointExplorationRunner` + analysis 배선, batch 완화.
5. **Phase 5 — Kafka 배선 + 수용-3**: `KafkaCaptureRunner` 레코드 헤더 주입(상관 방식은 Phase 1
   결과 반영).
6. **Phase 6 — attach 배선 + 수용-4**: 호스트 리시버 + override OTEL env + host-gateway.
7. **Phase 7 — 기본값 OTEL 전환 + 문서**: `--sql-capture` 기본 `otel`, docs/06·26·27 갱신.

각 경로(HTTP/Kafka/attach)는 Phase 1 결과에 따라 독립적으로 폴백 유지 가능(전부 아니면 일부만 전환).
