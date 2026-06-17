# OTEL Agent 기반 SQL 캡처 — 설계 (spec)

- 작성일: 2026-06-18
- 상위 로드맵: [docs/27 — 항목 1](../../27-roadmap-otel-capture-stub-seeding.md)
- 관련 문서: [docs/06 OTEL isolation](../../06-otel-isolation.md), [docs/24 exploration backends & InputOracle](../../24-exploration-backends-and-input-oracle.md), [docs/26 attach mode](../../26-attach-mode.md)

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
  - 생성자에 `OtlpTraceReceiver` 주입.
  - `begin()` → 16-byte traceId + 8-byte spanId 생성(빌더가 trace 생산자). `requestHeaders()` =
    `{"traceparent": "00-<traceId>-<spanId>-01"}`. (no `Math.random` — 단조 카운터 + 고정 시드
    파생으로 프로젝트의 재현성 원칙 준수. 구현은 plan에서.)
  - `drain()` → 리시버에서 **"injected spanId를 부모로 가지는 entry span"** 도착까지 await
    (timeout 보유) + quiescence drain. 그 trace의 DB span(`db.system`/`db.query.text`/
    `db.query.parameter.N`)을 span start 시각 순으로 `ParsedSql`로 환원.
    - `db.query.text`(placeholder `?`)는 그대로 `ParsedSql.sql`로 → 기존 컬럼 매핑 재사용.
    - `db.query.parameter.N`(0-based, String) → `ParsedSql.Binding(N+1, value)` (기존 1-based
      position 규약에 맞춰 +1 정규화).

- **`OtlpTraceReceiver`** (`io.graphrag.builder.coverage` 또는 신규 `…capture.otlp`)
  - 동적 포트 HTTP 서버. `POST /v1/traces` 수신, `Content-Type: application/json`(OTLP/JSON
    `ExportTraceServiceRequest`)을 Jackson으로 디코드.
  - 누적: `Map<traceId, List<SpanRecord>>` (thread-safe). `spanId`, `parentSpanId`, `name`,
    `kind`, `startNanos`, attributes(특히 `db.*`) 보존.
  - `awaitEntrySpan(traceId, parentSpanId, timeout)`, `spans(traceId)`, 그리고 quiescence 판정용
    `lastArrivalNanos(traceId)` 제공.
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

`InvocationOutcome`의 `logStart`/`logEnd`는 **drain된 SQL(또는 raw `ParsedSql`)** 로 대체한다.
log-parser 경로도 동일 인터페이스를 타므로 byte-offset 필드는 backend 내부 구현 디테일로 숨는다.

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
경로에선 유지.

### 4.4 통합 지점 (변경 파일)

- `gradle/libs.versions.toml` — `otelAgent` `2.14.0` → **`2.16.0`** (Phase 1 선행).
- `OtelAgent.env(serviceName)` — OTEL 모드: `OTEL_TRACES_EXPORTER=otlp`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/json`, `OTEL_EXPORTER_OTLP_ENDPOINT=<리시버 URL>`,
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`(필요 시), JDBC `capture-query-parameters=true` 활성.
  log-parser 모드: 기존 `none` 유지(baggage 전파만).
- `OtelAgent` — `capture-query-parameters` 플래그를 `otel.instrumentation.jdbc.experimental.
  capture-query-parameters=true`로 system property/JAVA_TOOL_OPTIONS 또는 `OTEL_INSTRUMENTATION_
  JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS=true` env로 주입.
- `EndpointExplorationRunner` — `doSend`에 `scope` 배선(위 4.2). 고정 150ms sleep 제거(OTEL은
  await, log-parser는 drain 시 짧은 settle).
- `KafkaCaptureRunner` — `publishAndCapture`에 `scope` 배선(위 4.3). 레코드 헤더 주입.
- `SutProcess.start` — analysis OTEL 모드 시 `SPRING_APPLICATION_JSON`에
  `spring.jpa.properties.hibernate.jdbc.batch_size=0` 추가(batch 완화).
- `AnalysisEnvironment` — `OtlpTraceReceiver` + 선택된 `SqlCaptureBackend` 배선. backend 선택은
  `--sql-capture otel|log` (기본 `otel`, PoC 통과 전엔 `log`).
- `AttachedComposeEnvironment` + `OverrideComposeGenerator` — 호스트 리시버 기동 + app 서비스에
  OTEL env 주입. 엔드포인트는 컨테이너→호스트 도달용으로 **`http://host.docker.internal:<port>`**
  (Mac/Win), Linux는 override에 `extra_hosts: ["host.docker.internal:host-gateway"]` 주입.
  backend는 동일 `OtelSpanCapture` 재사용.

## 5. 리스크 / 오픈 질문

1. **드라이버별 bind 값 노출** — `capture-query-parameters`가 petclinic 드라이버(MySQL/H2)에서
   실제 `db.query.parameter.N`을 내보내는지. → **Phase 1 PoC 게이트①**.
2. **Kafka context 전파 방식** — consumer process span이 주입 context의 **자식(같은 trace-id)**
   인지 **span link(다른 trace-id + 우리 trace 링크)** 인지가 agent 설정/버전에 따라 갈림.
   - 자식이면 trace-id 동등 매칭으로 끝.
   - link면 매칭 규칙을 **trace-id OR "injected trace로의 link 보유"** 로 확장.
   - 둘 다 불가하면 **Kafka만 log-parser 폴백 유지**.
   → **Phase 1 PoC 게이트②** (tainted-spring MSA의 Kafka consumer SUT로 검증).
3. **batch 미수집** — `batch_size=0`로 회피하되, 그래도 빈 SQL이면 해당 요청만 log-parser 폴백.
4. **async 결정성** — entry span 완료 신호 + quiescence 창 크기 튜닝. timeout 시 폴백 또는 경고.
5. **attach 네트워킹** — Linux `host-gateway`는 Docker 20.10+ 필요. 미지원 환경 감지 시 경고.
6. **리시버 동시성** — 동일 SUT에 여러 trace가 동시 흐를 수 있음(향후 병렬 탐색). traceId 키
   분리로 격리되지만, drain 후 메모리 정리(완료 trace 제거) 정책 필요.

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
  `db.query.parameter.N` 속성 추출), traceId 누적, `awaitEntrySpan`/quiescence 판정.
- `OtelSpanCapture` — fake 리시버로 trace-id 상관, entry span(parent=spanId) await, DB span →
  `ParsedSql` 환원(parameter index +1 정규화, 순서), timeout 동작.
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
