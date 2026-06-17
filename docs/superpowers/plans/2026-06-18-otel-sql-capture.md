# OTEL Agent 기반 SQL 캡처 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SUT 로그 byte-offset 기반 SQL 캡처를 교체 가능한 `SqlCaptureBackend` 뒤로 추상화하고, OTEL Java agent의 DB span을 trace-id로 요청에 귀속하는 `OtelSpanCapture`(HTTP+Kafka, analysis+attach)를 1순위 backend로 도입한다.

**Architecture:** 빌더가 in-process OTLP/JSON 리시버를 띄우고, SUT의 OTEL agent v2.16.0이 보내는 DB span(`db.query.text` + `db.query.parameter.*`)을 수신한다. 요청마다 고유 `traceparent`를 outbound(HTTP 헤더 / Kafka 레코드 헤더)로 주입해 그 trace의 DB span만 묶는다. 컬럼/테이블 매핑은 기존 `ParsedSql` 텍스트 파싱을 재사용하고, OTEL은 bind 값 소스 + 귀속만 대체한다. 기존 `SqlLogParser` 경로는 `LogParserCapture` 폴백으로 보존한다.

**Tech Stack:** Java 17, Gradle, JUnit 5, Testcontainers, OpenTelemetry Java agent 2.16.0, Jackson, `com.sun.net.httpserver.HttpServer`(JDK 내장 — 신규 의존성 없음).

**Spec:** [docs/superpowers/specs/2026-06-18-otel-sql-capture-design.md](../specs/2026-06-18-otel-sql-capture-design.md)

---

## File Structure

신규/수정 파일과 책임:

- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlCaptureBackend.java` — backend 인터페이스 + 내부 `Scope` 인터페이스.
- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/LogParserCapture.java` — 기존 로그 파서 경로를 인터페이스 뒤로(폴백/기본).
- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/TraceParent.java` — 결정적 W3C `traceparent` 생성기(runId prefix + 단조 카운터).
- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiver.java` — OTLP/JSON HTTP 수신기 + traceId→span 누적 + await/quiescence/remove.
- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/SpanRecord.java` — 디코드된 span 1건(불변 record).
- **Create** `graph-rag-builder/src/main/java/io/graphrag/builder/capture/OtelSpanCapture.java` — OTEL backend(상관/await/ParsedSql 환원/폴백).
- **Modify** `gradle/libs.versions.toml` — `otelAgent` 2.14.0 → 2.16.0.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/OtelAgent.java` — OTLP export env 추가(`otlpEnv` 오버로드).
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java` — `SqlCaptureBackend` 주입 + `doSend` scope 배선 + `captureSqlFromParsed(List<ParsedSql>)` 시그니처 + state-guard variant 경로(line 367).
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/explore/InvocationOutcome.java` — `List<ParsedSql> capturedSql` 필드 추가(drain 결과 운반; `logStart/logEnd` 보존).
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/explore/PathCandidate.java` — `List<ParsedSql> capturedSql` 필드 추가.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/explore/ExplorationOrchestrator.java` — `toOutcome`(line 75~93)에서 `capturedSql`을 outcome→candidate로 복사.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureRunner.java` — scope 배선 + 레코드 헤더 주입.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutProcess.java` — OTEL 모드 시 `hibernate.jdbc.batch_size=0` 병합.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java` — 리시버 start → URL 확정 → OTEL env 주입 + backend 노출.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/env/AttachedComposeEnvironment.java` + `OverrideComposeGenerator.java` — 호스트 리시버 + override OTEL env + host-gateway.
- **Modify** `graph-rag-builder/src/main/java/io/graphrag/builder/cli/BuilderCli.java` + `BuildConfig.java` — `--sql-capture otel|log` 플래그.
- **Tests** 각 신규 클래스별 단위 테스트 + 수용 테스트(e2e / BuilderE2eTest).

---

## Phase 1 — PoC 게이트 (go/no-go, TDD 아님 — spike)

> 이 Phase는 검증 스파이크다. 실패 시 해당 경로 폴백 유지·기본전환 보류. 결과를 plan 하단 "PoC 결과" 절에 기록하고 Phase 3/5의 인덱스 규약·Kafka 상관 방식을 확정한다.

### Task 1.1: otelAgent 2.16.0 bump

**Files:** Modify `gradle/libs.versions.toml`

- [ ] **Step 1: 버전 변경**

`gradle/libs.versions.toml`에서:
```toml
otelAgent = "2.16.0"
```
(기존 `otelAgent = "2.14.0"`)

- [ ] **Step 2: 번들 재생성 확인**

Run: `./gradlew :graph-rag-builder:processResources`
Expected: BUILD SUCCESSFUL. `graph-rag-builder/build/resources/main/agents/otel-javaagent.jar`가 2.16.0으로 갱신(파일 크기/날짜 변경).

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: bump otelAgent 2.14.0 -> 2.16.0 (OTEL SQL capture PoC)"
```

### Task 1.2: PoC① — HTTP에서 db.query.parameter 노출 + 인덱스 규약 실측

**Files:** Create (임시) `graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelParamPocTest.java`

목적: petclinic(또는 e2e가 쓰는 SUT)을 OTEL agent + `capture-query-parameters`로 띄우고, 단순 stdout exporter(또는 임시 OTLP 리시버)로 DB span 속성을 덤프해 **(a)** `db.query.parameter.N`이 실제로 나오는지, **(b)** N이 0-based인지 1-based인지 확인.

- [ ] **Step 1: 수동 검증 런 (가장 단순)**

Run (petclinic jar 경로는 `.work/spring-petclinic`의 빌드 산출물 사용):
```bash
java -javaagent:graph-rag-builder/build/resources/main/agents/otel-javaagent.jar \
  -Dotel.traces.exporter=logging \
  -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
  -Dotel.instrumentation.jdbc.experimental.capture-query-parameters=true \
  -DSERVER_PORT=18080 \
  -jar .work/spring-petclinic/target/spring-petclinic-*.jar &
# 부팅 후
curl -s -X POST localhost:18080/owners -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'firstName=A&lastName=B&address=x&city=y&telephone=1234567890'
# 로그에서 db.query.parameter.* 속성 grep
```
Expected: 콘솔 span 로그에 `db.query.text` + `db.query.parameter.0`(또는 `.1`) 속성이 보인다.

- [ ] **Step 2: 인덱스 규약 기록**

관찰한 첫 파라미터 키가 `.0`이면 **0-based**, `.1`이면 **1-based**. 이 결과를 plan 하단 "PoC 결과"에 적는다 (Phase 3에서 `ParsedSql.Binding` position 매핑에 사용).

- [ ] **Step 3: batch 확인**

INSERT가 여러 건일 때(예: owner+pet) Hibernate batch로 묶이면 파라미터가 비는지 확인. `-Dspring.jpa.properties.hibernate.jdbc.batch_size=0` 추가 시 파라미터가 나오는지 비교 기록.

> **게이트①**: `db.query.parameter.*`가 안 나오면 → HTTP OTEL 경로 보류(log-parser 유지), Phase 7 기본전환 제외. 결과를 기록하고 사용자에게 보고.

### Task 1.3: PoC② — Kafka consumer trace 상관 방식 실측

**Files:** 검증 런(tainted-spring MSA의 Kafka consumer SUT — `--with-kafka` 대상)

- [ ] **Step 1: 레코드 헤더 traceparent 주입 + 상관 확인**

Kafka consumer SUT를 OTEL agent(`otel.traces.exporter=logging`, `capture-query-parameters=true`)로 띄우고, `traceparent` 헤더를 단 레코드를 발행한 뒤 consumer가 만든 DB span의 trace-id를 관찰:
```bash
# (KafkaProducer로 ProducerRecord에 header "traceparent"="00-<32hex>-<16hex>-01" 추가해 발행)
```
관찰: consumer process span의 traceId가 주입한 traceId와 **같은지(child)** 또는 **다르고 links에 주입 traceId가 있는지(link)**.

- [ ] **Step 2: 상관 방식 기록**

`child`(같은 traceId) 또는 `link`(다른 traceId + link)를 "PoC 결과"에 기록. Phase 5의 `awaitEntrySpan` 매칭 전략을 이 결과로 확정.

> **게이트②**: 둘 다 불가(traceId도 link도 주입값과 무관)하면 → Kafka는 log-parser 유지, Phase 5는 헤더 주입만 두고 캡처는 폴백.

---

## Phase 2 — backend 인터페이스 + LogParserCapture 이동

### Task 2.1: SqlCaptureBackend / Scope 인터페이스

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlCaptureBackend.java`

- [ ] **Step 1: 인터페이스 작성**

```java
package io.graphrag.builder.capture;

import java.util.List;
import java.util.Map;

/**
 * 요청 1건이 유발한 SQL 캡처를 추상화한다. 구현: LogParserCapture(폴백), OtelSpanCapture(1순위).
 * begin()으로 요청 경계를 열고, 호출자는 requestHeaders()를 outbound(HTTP/Kafka)로 주입한 뒤
 * 요청을 보내고, drain()으로 그 요청의 SQL을 순서 보존하여 회수한다.
 */
public interface SqlCaptureBackend {

    Scope begin();

    interface Scope {
        /** 요청에 주입할 상관 헤더 (OTEL: traceparent 1개, log-parser: 빈 맵). transport-agnostic. */
        Map<String, String> requestHeaders();

        /** begin() 이후 SUT가 발행한 SQL + 바인딩 (발행 순서). */
        List<ParsedSql> drain();

        /** drain()을 기대 SQL 출현까지 폴링/await하는 변형 (timeout ms). 폴백/Kafka happy 경로용. */
        default List<ParsedSql> drain(long timeoutMillis) {
            return drain();
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :graph-rag-builder:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/SqlCaptureBackend.java
git commit -m "feat(capture): SqlCaptureBackend interface (begin/Scope/drain)"
```

### Task 2.2: LogParserCapture (기존 경로 위임)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/LogParserCapture.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/LogParserCaptureTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserCaptureTest {

    /** begin()→drain()이 [logStart, 현재offset) 구간만 파싱한다. */
    @Test
    void drain_parsesOnlyRangeSinceBegin() {
        StringBuilder logBuf = new StringBuilder("noise before\n");
        AtomicLong offset = new AtomicLong(logBuf.toString().getBytes().length);
        SutHandle sut = new FakeSut(logBuf, offset);

        SqlCaptureBackend backend = new LogParserCapture(sut);
        SqlCaptureBackend.Scope scope = backend.begin();   // logStart 캡처

        logBuf.append("org.hibernate.SQL : insert into owners (first_name) values (?)\n");
        logBuf.append("org.hibernate.orm.jdbc.bind : binding parameter (1:VARCHAR) <- [Alice]\n");
        offset.set(logBuf.toString().getBytes().length);

        List<ParsedSql> sql = scope.drain();
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0).sql()).contains("insert into owners");
        assertThat(sql.get(0).bindings()).extracting(ParsedSql.Binding::value).containsExactly("Alice");
    }

    @Test
    void requestHeaders_empty() {
        assertThat(new LogParserCapture(new FakeSut(new StringBuilder(), new AtomicLong()))
                .begin().requestHeaders()).isEmpty();
    }

    /** 테스트용 SutHandle: in-memory 로그 버퍼 + 외부 제어 offset. */
    private static final class FakeSut implements SutHandle {
        private final StringBuilder buf;
        private final AtomicLong offset;
        FakeSut(StringBuilder buf, AtomicLong offset) { this.buf = buf; this.offset = offset; }
        @Override public String baseUri() { return "http://localhost:0"; }
        @Override public String readLog() { return buf.toString(); }
        @Override public long logOffset() { return offset.get(); }
        @Override public String readLogFrom(long o) { return readLogRange(o, Long.MAX_VALUE); }
        @Override public String readLogRange(long start, long end) {
            byte[] b = buf.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int from = (int) Math.min(Math.max(start, 0), b.length);
            int to = (int) Math.min(Math.max(end, 0), b.length);
            return from >= to ? "" : new String(b, from, to - from, java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public void stop() { }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*LogParserCaptureTest*'`
Expected: FAIL — `LogParserCapture` 클래스 없음(컴파일 에러).

- [ ] **Step 3: 구현**

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.env.SutHandle;

import java.util.List;
import java.util.Map;

/** 기존 SqlLogParser + byte-offset 경로를 SqlCaptureBackend 뒤로. OTEL 폴백/기본. */
public final class LogParserCapture implements SqlCaptureBackend {

    /** doSend가 의존하던 150ms 콘솔 flush 여유를 drain 내부로 이동. */
    private static final long SETTLE_MILLIS = 150;

    private final SutHandle sut;

    public LogParserCapture(SutHandle sut) {
        this.sut = sut;
    }

    @Override
    public Scope begin() {
        long logStart = sut.logOffset();
        return new Scope() {
            @Override public Map<String, String> requestHeaders() { return Map.of(); }

            @Override public List<ParsedSql> drain() {
                sleep(SETTLE_MILLIS);
                return SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
            }

            @Override public List<ParsedSql> drain(long timeoutMillis) {
                long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
                while (System.nanoTime() < deadline) {
                    sleep(SETTLE_MILLIS);
                    List<ParsedSql> sql = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                    if (!sql.isEmpty()) {
                        sleep(SETTLE_MILLIS);   // 후속 flush 여유
                        return SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                    }
                }
                return List.of();
            }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*LogParserCaptureTest*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/LogParserCapture.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/LogParserCaptureTest.java
git commit -m "feat(capture): LogParserCapture backend (existing log path behind interface)"
```

---

## Phase 3 — OTLP 리시버 + traceparent + OtelSpanCapture

### Task 3.1: TraceParent 결정적 생성기

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/TraceParent.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/TraceParentTest.java`

- [ ] **Step 1: 실패 테스트**

```java
package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceParentTest {

    @Test
    void format_isW3C() {
        TraceParent tp = new TraceParent("run-1");
        TraceParent.Ids ids = tp.next();
        assertThat(ids.header()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(ids.traceId()).hasSize(32);
        assertThat(ids.spanId()).hasSize(16);
    }

    @Test
    void deterministic_sameRunSameSequence() {
        assertThat(new TraceParent("run-1").next().header())
                .isEqualTo(new TraceParent("run-1").next().header());
    }

    @Test
    void unique_acrossRequestsAndRuns() {
        TraceParent tp = new TraceParent("run-1");
        assertThat(tp.next().traceId()).isNotEqualTo(tp.next().traceId());          // 요청별
        assertThat(new TraceParent("run-1").next().traceId())
                .isNotEqualTo(new TraceParent("run-2").next().traceId());           // 런별
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*TraceParentTest*'`
Expected: FAIL — 클래스 없음.

- [ ] **Step 3: 구현**

```java
package io.graphrag.builder.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 결정적 W3C traceparent 생성기. runId 시드 + 요청별 단조 카운터를 SHA-256으로 섞어
 * 16-byte traceId / 8-byte spanId를 만든다. Math.random / new Date 미사용 (재현성).
 */
public final class TraceParent {

    private final byte[] seed;
    private final AtomicLong counter = new AtomicLong();

    public TraceParent(String runId) {
        this.seed = runId.getBytes(StandardCharsets.UTF_8);
    }

    public Ids next() {
        long n = counter.getAndIncrement();
        byte[] digest = sha256(seed, n);
        String traceId = hex(digest, 0, 16);
        String spanId = hex(digest, 16, 8);
        return new Ids(traceId, spanId);
    }

    public record Ids(String traceId, String spanId) {
        public String header() {
            return "00-" + traceId + "-" + spanId + "-01";
        }
    }

    private static byte[] sha256(byte[] seed, long n) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(seed);
            for (int i = 0; i < 8; i++) {
                md.update((byte) (n >>> (i * 8)));
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*TraceParentTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/TraceParent.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/TraceParentTest.java
git commit -m "feat(capture): deterministic W3C traceparent generator"
```

### Task 3.2: SpanRecord

**Files:** Create `graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/SpanRecord.java`

- [ ] **Step 1: 구현 (단순 record — 별도 테스트 없이 3.3에서 검증)**

```java
package io.graphrag.builder.capture.otlp;

import java.util.List;
import java.util.Map;

/**
 * OTLP/JSON에서 디코드된 span 1건 (필요한 필드만).
 * attributes는 평탄화된 문자열 맵(예: "db.query.text", "db.query.parameter.0").
 * links는 이 span이 참조하는 traceId 목록.
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        long startUnixNano,
        Map<String, String> attributes,
        List<String> linkedTraceIds) {
}
```

- [ ] **Step 2: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/SpanRecord.java
git commit -m "feat(capture): SpanRecord model for decoded OTLP spans"
```

### Task 3.3: OtlpTraceReceiver (OTLP/JSON 디코드 + 누적 + await/remove)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiver.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiverTest.java`

- [ ] **Step 1: 실패 테스트 (실제 HTTP POST로 OTLP/JSON 전송)**

```java
package io.graphrag.builder.capture.otlp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpTraceReceiverTest {

    private OtlpTraceReceiver receiver;

    @AfterEach
    void tearDown() { if (receiver != null) receiver.stop(); }

    /** 최소 OTLP/JSON ExportTraceServiceRequest: 1 resourceSpan → 1 scopeSpan → 1 span. */
    private static String otlpJson(String traceId, String spanId, String parentSpanId,
                                   String kind, String sql, String param0) {
        return """
            {"resourceSpans":[{"scopeSpans":[{"spans":[{
              "traceId":"%s","spanId":"%s","parentSpanId":"%s",
              "name":"INSERT owners","kind":%s,"startTimeUnixNano":"1000",
              "attributes":[
                {"key":"db.query.text","value":{"stringValue":"%s"}},
                {"key":"db.query.parameter.0","value":{"stringValue":"%s"}}
              ]
            }]}]}]}""".formatted(traceId, spanId, parentSpanId, kind, sql, param0);
    }

    private int post(String body) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(receiver.endpoint() + "/v1/traces"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    @Test
    void receivesAndIndexesSpansByTrace() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        String tid = "0".repeat(31) + "1";
        int status = post(otlpJson(tid, "a".repeat(16), "b".repeat(16),
                "SPAN_KIND_CLIENT", "insert into owners (first_name) values (?)", "Alice"));
        assertThat(status).isEqualTo(200);
        assertThat(receiver.spans(tid)).hasSize(1);
        assertThat(receiver.spans(tid).get(0).attributes()).containsEntry("db.query.parameter.0", "Alice");
    }

    @Test
    void awaitEntrySpan_returnsWhenChildOfInjectedSpan() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        String tid = "1".repeat(32);
        String injectedSpanId = "c".repeat(16);
        // entry(server/consumer) span: parent == injectedSpanId
        post(otlpJson(tid, "d".repeat(16), injectedSpanId,
                "SPAN_KIND_SERVER", "select 1", "x"));
        assertThat(receiver.awaitEntrySpan(tid, injectedSpanId, 1000)).isTrue();
    }

    @Test
    void awaitEntrySpan_timesOutWhenAbsent() {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        assertThat(receiver.awaitEntrySpan("2".repeat(32), "e".repeat(16), 200)).isFalse();
    }

    @Test
    void remove_clearsTrace() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        String tid = "3".repeat(32);
        post(otlpJson(tid, "a".repeat(16), "b".repeat(16), "SPAN_KIND_CLIENT", "select 1", "x"));
        assertThat(receiver.spans(tid)).isNotEmpty();
        receiver.remove(tid);
        assertThat(receiver.spans(tid)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*OtlpTraceReceiverTest*'`
Expected: FAIL — 클래스 없음.

> **추가 테스트(리뷰 반영)** — 같은 클래스에 더 작성:
> - `anyValue_intAndBoolNormalized`: `db.query.parameter.0`을 `{"intValue":"7"}`, `.1`을 `{"boolValue":true}`로 보낸 OTLP를 POST → `attributes`가 `"7"`, `"true"`로 정규화됨.
> - `concurrentPosts_noLoss`: 2개 스레드가 서로 다른 traceId로 동시에 N건 POST → 각 traceId의 `spans()` 크기가 정확(thread-safe 검증).
> - `addForTest_seedsWithoutHttp`: `receiver.addForTest(span)` 후 `spans(traceId)`에 보임(start() 불필요).

- [ ] **Step 3: 구현**

```java
package io.graphrag.builder.capture.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process OTLP/JSON 트레이스 수신기. SUT의 OTEL agent가 POST /v1/traces 로 보내는
 * ExportTraceServiceRequest(JSON)를 디코드해 traceId별로 span을 누적한다. (JDK 내장 HttpServer)
 */
public final class OtlpTraceReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpTraceReceiver.class);

    // 값은 thread-safe 리스트 — HttpServer 워커 스레드가 add하고 main 스레드가 읽는다 (리뷰 반영).
    private final Map<String, List<SpanRecord>> byTrace = new ConcurrentHashMap<>();
    private final Map<String, Long> lastArrivalNanos = new ConcurrentHashMap<>();
    private HttpServer server;

    public void start() {
        try {
            // 0.0.0.0 바인드 — attach 모드에서 컨테이너가 host.docker.internal로 도달해야 함 (리뷰 반영).
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/v1/traces", exchange -> {
                try {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    ingest(Json.mapper().readTree(body));
                    exchange.sendResponseHeaders(200, 0);
                } catch (Exception e) {
                    log.warn("otlp ingest failed", e);
                    exchange.sendResponseHeaders(500, 0);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            log.info("otlp receiver on {}", endpoint());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start otlp receiver", e);
        }
    }

    /** analysis: SUT(호스트 프로세스)가 도달할 base URL (loopback). */
    public String endpoint() {
        return "http://127.0.0.1:" + port();
    }

    /** attach: 컨테이너 SUT가 도달할 base URL (host-gateway). */
    public String hostEndpoint() {
        return "http://host.docker.internal:" + port();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void ingest(JsonNode root) {
        for (JsonNode rs : root.path("resourceSpans")) {
            for (JsonNode ss : rs.path("scopeSpans")) {
                for (JsonNode span : ss.path("spans")) {
                    record(toRecord(span));
                }
            }
        }
    }

    /** 단위 테스트용 시드 훅 (HTTP 없이 span 주입). final class 유지를 위해 상속 대신 이 메서드 사용 (리뷰 반영). */
    void addForTest(SpanRecord span) {
        record(span);
    }

    private void record(SpanRecord span) {
        byTrace.computeIfAbsent(span.traceId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(span);
        lastArrivalNanos.put(span.traceId(), System.nanoTime());
    }

    private static SpanRecord toRecord(JsonNode span) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (JsonNode a : span.path("attributes")) {
            attrs.put(a.path("key").asText(), anyValueToString(a.path("value")));
        }
        List<String> linkedTraces = new ArrayList<>();
        for (JsonNode link : span.path("links")) {
            linkedTraces.add(link.path("traceId").asText());
        }
        return new SpanRecord(
                span.path("traceId").asText(),
                span.path("spanId").asText(),
                span.path("parentSpanId").asText(),
                span.path("name").asText(),
                span.path("kind").asText(),
                parseNano(span.path("startTimeUnixNano").asText()),
                attrs,
                linkedTraces);
    }

    private static long parseNano(String s) {
        try { return s.isEmpty() ? 0 : Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** OTLP AnyValue → 문자열. stringValue 외 int/double/bool도 정규화 (bind 값은 숫자/불리언 가능, 리뷰 반영). */
    private static String anyValueToString(JsonNode value) {
        if (value.has("stringValue")) { return value.path("stringValue").asText(); }
        if (value.has("intValue")) { return value.path("intValue").asText(); }
        if (value.has("doubleValue")) { return value.path("doubleValue").asText(); }
        if (value.has("boolValue")) { return value.path("boolValue").asText(); }
        return value.path("stringValue").asText();   // 그 외(bytes/array)는 best-effort 빈 문자열
    }

    public List<SpanRecord> spans(String traceId) {
        return List.copyOf(byTrace.getOrDefault(traceId, List.of()));
    }

    /** parentSpanId == injectedSpanId 인 entry span이 도착할 때까지 await (poll 50ms). */
    public boolean awaitEntrySpan(String traceId, String injectedSpanId, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (spans(traceId).stream().anyMatch(s -> injectedSpanId.equals(s.parentSpanId()))) {
                return true;
            }
            sleep(50);
        }
        return false;
    }

    /** 마지막 span 도착 이후 quiescenceMillis 동안 신규 span이 없으면 true. */
    public boolean isQuiescent(String traceId, long quiescenceMillis) {
        Long last = lastArrivalNanos.get(traceId);
        return last != null && (System.nanoTime() - last) >= quiescenceMillis * 1_000_000L;
    }

    public void remove(String traceId) {
        byTrace.remove(traceId);
        lastArrivalNanos.remove(traceId);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void stop() {
        if (server != null) { server.stop(0); }
    }

    @Override public void close() { stop(); }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*OtlpTraceReceiverTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiver.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiverTest.java
git commit -m "feat(capture): in-process OTLP/JSON trace receiver (await/quiescence/remove)"
```

### Task 3.4: OtelSpanCapture (상관 + DB span → ParsedSql + 폴백)

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/OtelSpanCapture.java`
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelSpanCaptureTest.java`

> **PoC 결과 반영**: 아래 `PARAM_INDEX_BASE` 상수를 PoC①에서 확정한 값(0-based면 0, 1-based면 1)으로 둔다. 테스트도 그 값에 맞춘다. (초안은 0-based 가정 — PoC가 1-based면 수정.)

- [ ] **Step 1: 실패 테스트 (fake 리시버에 span을 직접 넣어 drain 검증)**

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.capture.otlp.OtlpTraceReceiver;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.env.SutHandle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelSpanCaptureTest {

    /** 실제 OtlpTraceReceiver(미기동)에 addForTest로 시드. TraceParent가 결정적이라
     *  begin()이 만들 traceId/spanId를 OtelScope getter로 얻어 시드 — 상속/rebind 불필요 (리뷰 반영). */
    @Test
    void drain_mapsDbSpansToParsedSqlInStartOrder() {
        OtlpTraceReceiver receiver = new OtlpTraceReceiver();   // start() 호출 안 함 (HTTP 불필요)
        OtelSpanCapture capture = new OtelSpanCapture(receiver, noopSut(), new TraceParent("run-1"));
        OtelSpanCapture.OtelScope scope = (OtelSpanCapture.OtelScope) capture.begin();  // 같은 패키지
        String tid = scope.traceId();
        String injected = scope.spanId();

        receiver.addForTest(dbSpan(tid, "s1", 200, "update owners set city=? where id=?", "Seoul", "7"));
        receiver.addForTest(dbSpan(tid, "s2", 100, "insert into owners (first_name) values (?)", "Alice"));
        receiver.addForTest(entrySpan(tid, injected, 50));   // entry span: parent == injected spanId

        List<ParsedSql> sql = scope.drain();
        // start 시각 순서: insert(100) → update(200)
        assertThat(sql).extracting(ParsedSql::sql)
                .containsExactly("insert into owners (first_name) values (?)",
                                 "update owners set city=? where id=?");
        assertThat(sql.get(0).bindings()).extracting(ParsedSql.Binding::value).containsExactly("Alice");
        // PARAM_INDEX_BASE 적용 후 1-based position 규약 (PoC 확정값에 맞춰 상수 조정)
        assertThat(sql.get(0).bindings().get(0).position()).isEqualTo(1);
    }

    private static SpanRecord dbSpan(String tid, String spanId, long start, String sql, String... params) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("db.query.text", sql);
        for (int i = 0; i < params.length; i++) attrs.put("db.query.parameter." + i, params[i]);
        return new SpanRecord(tid, spanId, "root", "db", "SPAN_KIND_CLIENT", start, attrs, List.of());
    }
    private static SpanRecord entrySpan(String tid, String injected, long start) {
        return new SpanRecord(tid, "f".repeat(16), injected, "GET /x", "SPAN_KIND_SERVER", start, Map.of(), List.of());
    }
    private static SutHandle noopSut() {
        return new SutHandle() {
            public String baseUri() { return ""; }
            public String readLog() { return ""; }
            public long logOffset() { return 0; }
            public String readLogFrom(long o) { return ""; }
            public String readLogRange(long s, long e) { return ""; }
            public void stop() { }
        };
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*OtelSpanCaptureTest*'`
Expected: FAIL — `OtelSpanCapture` 없음.

- [ ] **Step 3: 구현**

> `OtelSpanCapture`는 `OtlpTraceReceiver`를 직접 받는다(public 메서드 + 패키지-가시 `addForTest`만 사용 — `OtlpTraceReceiver`는 `final` 유지). 테스트는 위처럼 `addForTest`로 시드한다.

```java
package io.graphrag.builder.capture;

import io.graphrag.builder.capture.otlp.OtlpTraceReceiver;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.env.SutHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OTEL DB span을 trace-id로 요청에 귀속하는 1순위 backend.
 * begin()이 요청별 traceparent를 발급, drain()이 entry span 완료 await + quiescence 후
 * 그 trace의 DB span을 ParsedSql로 환원한다. 비면 logStart 기준 log-parser 폴백.
 */
public final class OtelSpanCapture implements SqlCaptureBackend {

    private static final Logger log = LoggerFactory.getLogger(OtelSpanCapture.class);

    /** PoC①에서 확정: db.query.parameter.N의 N이 0-based이면 0, 1-based이면 1. */
    static final int PARAM_INDEX_BASE = 0;

    static final long AWAIT_TIMEOUT_MILLIS = 8_000;
    static final long QUIESCENCE_MILLIS = 250;
    private static final long POLL_MILLIS = 50;

    private final OtlpTraceReceiver receiver;
    private final SutHandle sut;
    private final TraceParent traceParent;

    public OtelSpanCapture(OtlpTraceReceiver receiver, SutHandle sut, TraceParent traceParent) {
        this.receiver = receiver;
        this.sut = sut;
        this.traceParent = traceParent;
    }

    @Override
    public Scope begin() {
        TraceParent.Ids ids = traceParent.next();
        long logStart = sut.logOffset();
        return new OtelScope(ids, logStart);
    }

    public final class OtelScope implements Scope {
        private final TraceParent.Ids ids;
        private final long logStart;

        OtelScope(TraceParent.Ids ids, long logStart) {
            this.ids = ids;
            this.logStart = logStart;
        }

        public String traceId() { return ids.traceId(); }
        public String spanId() { return ids.spanId(); }

        @Override public Map<String, String> requestHeaders() {
            return Map.of("traceparent", ids.header());
        }

        @Override public List<ParsedSql> drain() { return drain(AWAIT_TIMEOUT_MILLIS); }

        @Override public List<ParsedSql> drain(long timeoutMillis) {
            try {
                boolean arrived = receiver.awaitEntrySpan(ids.traceId(), ids.spanId(), timeoutMillis);
                if (arrived) {
                    waitForQuiescence(ids.traceId());
                    List<ParsedSql> sql = toParsedSql(receiver.spans(ids.traceId()));
                    if (!sql.isEmpty()) {
                        return sql;
                    }
                }
                // 폴백: OTEL이 비었거나 timeout → 로그 파서 1회
                List<ParsedSql> fallback = SqlLogParser.parse(sut.readLogRange(logStart, sut.logOffset()));
                if (!arrived) {
                    log.warn("otel entry span timeout (trace={}), fell back to log-parser ({} sql)",
                            ids.traceId(), fallback.size());
                }
                return fallback;
            } finally {
                receiver.remove(ids.traceId());
            }
        }

        private void waitForQuiescence(String traceId) {
            long deadline = System.nanoTime() + AWAIT_TIMEOUT_MILLIS * 1_000_000L;
            while (System.nanoTime() < deadline && !receiver.isQuiescent(traceId, QUIESCENCE_MILLIS)) {
                sleep(POLL_MILLIS);
            }
        }
    }

    /** DB span만 골라 start 순서로 ParsedSql 환원. db.query.text + db.query.parameter.N. */
    private static List<ParsedSql> toParsedSql(List<SpanRecord> spans) {
        List<ParsedSql> result = new ArrayList<>();
        List<SpanRecord> dbSpans = new ArrayList<>(spans.stream()
                .filter(s -> s.attributes().containsKey("db.query.text")).toList());
        dbSpans.sort(Comparator.comparingLong(SpanRecord::startUnixNano));
        for (SpanRecord span : dbSpans) {
            String sql = span.attributes().get("db.query.text");
            TreeMap<Integer, String> ordered = new TreeMap<>();
            span.attributes().forEach((k, v) -> {
                if (k.startsWith("db.query.parameter.")) {
                    ordered.put(Integer.parseInt(k.substring("db.query.parameter.".length())), v);
                }
            });
            List<ParsedSql.Binding> bindings = new ArrayList<>();
            ordered.forEach((idx, value) ->
                    bindings.add(new ParsedSql.Binding(idx - PARAM_INDEX_BASE + 1, value)));
            result.add(new ParsedSql(sql, bindings));
        }
        return result;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> 주: `drain()`의 quiescence 대기로 테스트는 ~250ms(`QUIESCENCE_MILLIS`) 소요. 시드를 `drain()` 호출 전에 넣으므로 첫 폴에서 quiescent 판정된다. `OtlpTraceReceiver`는 `start()` 없이 `addForTest`만으로 시드 가능(HTTP 불필요).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :graph-rag-builder:test --tests '*OtelSpanCaptureTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add graph-rag-builder/src/main/java/io/graphrag/builder/capture/OtelSpanCapture.java \
        graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelSpanCaptureTest.java
git commit -m "feat(capture): OtelSpanCapture (trace-id correlation, db span -> ParsedSql, log fallback)"
```

---

## Phase 4 — HTTP 배선 + analysis + 수용-1/2

### Task 4.1: OtelAgent OTLP export env

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/coverage/OtelAgent.java`

- [ ] **Step 1: 테스트**

`graph-rag-builder/src/test/java/io/graphrag/builder/coverage/OtelAgentTest.java`:
```java
package io.graphrag.builder.coverage;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class OtelAgentTest {
    @Test
    void otlpEnv_hasExporterAndEndpoint(@org.junit.jupiter.api.io.TempDir Path dir) {
        var env = OtelAgent.prepare(dir).otlpEnv("svc", "http://127.0.0.1:4318");
        assertThat(env).containsEntry("OTEL_TRACES_EXPORTER", "otlp")
                .containsEntry("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")
                .containsEntry("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
                .containsEntry("OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS", "true");
        assertThat(env.get("OTEL_PROPAGATORS")).contains("tracecontext");
    }
}
```

- [ ] **Step 2: 실패 확인** → Run: `./gradlew :graph-rag-builder:test --tests '*OtelAgentTest*'` → FAIL (`otlpEnv` 없음).

- [ ] **Step 3: 구현** — `OtelAgent`에 메서드 추가(기존 `env`는 유지):
```java
    public Map<String, String> otlpEnv(String serviceName, String otlpEndpoint) {
        return Map.of(
                "OTEL_TRACES_EXPORTER", "otlp",
                "OTEL_METRICS_EXPORTER", "none",
                "OTEL_LOGS_EXPORTER", "none",
                "OTEL_EXPORTER_OTLP_PROTOCOL", "http/json",
                "OTEL_EXPORTER_OTLP_ENDPOINT", otlpEndpoint,
                "OTEL_BSP_SCHEDULE_DELAY", "100",
                "OTEL_INSTRUMENTATION_JDBC_EXPERIMENTAL_CAPTURE_QUERY_PARAMETERS", "true",
                "OTEL_PROPAGATORS", "tracecontext,baggage",
                "OTEL_SERVICE_NAME", serviceName);
    }
```

- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** → `feat(coverage): OtelAgent.otlpEnv (otlp/json export + capture-query-parameters)`

### Task 4.2: batch_size=0 병합 (SutProcess)

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/env/SutProcess.java`

- [ ] **Step 1: 테스트** — `loggingJson`을 패키지-가시로 노출하거나 별도 정적 메서드로 분리해 검증.
`SutProcessTest`:
```java
@Test
void loggingJson_includesBatchSizeZeroWhenRequested() {
    String json = SutProcess.springApplicationJson(java.util.Map.of(), true);
    assertThat(json).contains("\"spring.jpa.properties.hibernate.jdbc.batch_size\":\"0\"");
    assertThat(json).contains("org.hibernate.SQL");   // 기존 logging 유지
}
```

- [ ] **Step 2: 실패 확인** → FAIL.

- [ ] **Step 3: 구현** — 기존 `private static String loggingJson(Map<String,String> extraLevels)`를 **`static String springApplicationJson(Map<String,String> extraLevels, boolean disableBatch)`로 개명**(`private` 제거 → 패키지-가시, 테스트 접근). 옛 `loggingJson` 메서드는 제거하고 `start()`의 호출부(`SPRING_APPLICATION_JSON` 주입)를 새 이름으로 교체. `disableBatch`면 `"spring.jpa.properties.hibernate.jdbc.batch_size":"0"`를 같은 JSON object에 추가. `SutOptions`에 `boolean disableHibernateBatch` 필드 추가(기본 false; 호출부는 Task 4.5 Step 4에서 일괄 갱신). `start()`가 `options.disableHibernateBatch()`를 넘김.

- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** → `feat(env): merge hibernate.jdbc.batch_size=0 into SPRING_APPLICATION_JSON (OTEL mode)`

### Task 4.3: EndpointExplorationRunner scope 배선

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/run/EndpointExplorationRunner.java`

- [ ] **Step 1: InvocationOutcome / PathCandidate에 capturedSql 필드 추가 (전파 통로 — 리뷰 반영)**

`doSend`의 drain 결과는 나중에 `buildPaths`(line 500)와 state-guard 경로(line 367)에서 SQL을 조립할 때 필요하다. 현재 흐름은 `InvocationOutcome(logStart,logEnd)` → `ExplorationOrchestrator.toOutcome`(line 75~93)이 `PathCandidate(logStart,logEnd)`로 복사 → `buildPaths`가 `captureSql(candidate)` → `captureSqlForRange(candidate.logStart(), candidate.logEnd())`로 **재파싱**한다. 이 통로에 `List<ParsedSql> capturedSql`을 추가한다.

- `InvocationOutcome`(record)에 마지막 컴포넌트로 `List<ParsedSql> capturedSql` 추가. 기존 편의 생성자들은 `List.of()`로 채워 호환 유지(`logStart/logEnd`도 그대로 둠).
- `PathCandidate`(record, line 9~)에 `List<ParsedSql> capturedSql` 추가(편의 생성자 `List.of()` 기본).
- `ExplorationOrchestrator.toOutcome`(line 75~93)에서 `proto.input().outcome().capturedSql()`을 candidate/outcome으로 복사.

- [ ] **Step 2: 생성자에 `SqlCaptureBackend sqlCapture` 추가**

`EndpointExplorationRunner` 생성자(line 97~)에 파라미터 추가, 필드 보관. 유일한 production 호출 사이트는 `BuilderCli` line 437(`new EndpointExplorationRunner(...)`) — 여기에 선택된 backend 전달(Task 4.5). 직접 인스턴스화하는 단위 테스트는 없음(grep `new EndpointExplorationRunner`로 확인 — BuilderCli 1곳).

- [ ] **Step 3: `doSend` 배선** (line 653~694)

- `long logStart = sut.logOffset();` 다음에 `SqlCaptureBackend.Scope sqlScope = sqlCapture.begin();` 추가.
- `.header("baggage", "test-id=explore")` 유지. **traceparent override 시맨틱(리뷰 반영)**: Java `HttpRequest.Builder.header`는 같은 이름을 *추가*만 한다(치환 아님). 따라서 사용자 `extraHeaders.resolved(...)`를 적용할 때 **예약 키(`traceparent`, case-insensitive)를 건너뛰고**, backend 헤더를 마지막에 1회만 주입한다:
```java
for (Map.Entry<String, String> h : extraHeaders.resolved(Instant.now()).entrySet()) {
    if (h.getKey().equalsIgnoreCase("traceparent")) {
        log.warn("user header 'traceparent' ignored — reserved by SQL capture backend");
        continue;
    }
    builder.header(h.getKey(), h.getValue());
}
for (Map.Entry<String, String> h : sqlScope.requestHeaders().entrySet()) {
    builder.header(h.getKey(), h.getValue());   // traceparent (OTEL) / 없음 (log)
}
```
- `Thread.sleep(150);` **제거**(LogParserCapture.drain 내부 settle이 대체).
- `long logEnd = sut.logOffset();` 유지(호환). `List<ParsedSql> drained = sqlScope.drain();`.
- `InvocationOutcome` 생성에 `drained`를 `capturedSql` 인자로 전달.

> 주의: 부정-인증 패스 등 `doSend`를 재사용하는 모든 경로가 같은 scope 흐름을 타도록 한다.

- [ ] **Step 4: 컴파일/기존 테스트** → Run: `./gradlew :graph-rag-builder:test` (log backend 기본이므로 동작 동일).
- [ ] **Step 5: Commit** → `feat(run): carry per-request ParsedSql through InvocationOutcome/PathCandidate; wire scope into doSend`

### Task 4.4: captureSqlFromParsed 시그니처 전환 + 모든 캡처 경로 사용

**Files:** Modify `EndpointExplorationRunner.java`

- [ ] **Step 1: 메서드 개명·전환 (이름 충돌 회피 — 리뷰 반영)**

`captureSqlForRange(pathId, body, logStart, logEnd)`(line 395~416)을 **`captureSqlFromParsed(String pathId, JsonNode body, List<ParsedSql> parsed)`** 로 개명한다(기존 private `captureSql(PathCandidate)` line 936과의 충돌 회피). 본문에서 `SqlLogParser.parse(sut.readLogRange(...))` 줄 제거, 파라미터 `parsed` 직접 사용(컬럼 매핑·origin 분류·CapturedSql 조립 동일).

- [ ] **Step 2: 세 캡처 경로를 capturedSql 기반으로 전환**

1. `captureSql(PathCandidate candidate)`(line 936~937): `return captureSqlFromParsed(candidate.pathId(), candidate.body(), candidate.capturedSql());`
2. state-guard variant(line 367): `captureSqlForRange(pathId, body, out.logStart(), out.logEnd())` → `captureSqlFromParsed(pathId, body, out.capturedSql())`.
3. 그 외 `captureSqlForRange` 잔여 호출부가 있으면 동일 전환(grep `captureSqlForRange`로 0건 확인).

- [ ] **Step 3: 기존 테스트 green** → Run: `./gradlew :graph-rag-builder:test`
- [ ] **Step 4: Commit** → `refactor(run): captureSqlFromParsed uses backend-drained ParsedSql across all paths`

### Task 4.5: AnalysisEnvironment 리시버 + backend 배선

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/env/AnalysisEnvironment.java`, `BuilderCli.java`, `BuildConfig.java`

- [ ] **Step 1: `--sql-capture` 플래그 (record 생성자 호출부 주의 — 리뷰 반영)**

`BuildConfig`(record)에 `String sqlCapture` 필드 추가. `BuildConfig`는 편의 생성자가 여럿이므로(canonical + 편의 2개) **모든 편의 생성자에 기본값 `"log"`를 전달**하고, 카노니컬 생성자 호출 테스트가 있으면 인자를 추가한다(grep `new BuildConfig(`로 호출부 열거). `BuilderCli` 인자 파싱에 `--sql-capture otel|log` 추가(미지정 시 `"log"`).

- [ ] **Step 2: 리시버 소유(Environment) + backend는 start 이후 생성 (null sut 회피 — 리뷰 반영)**

spec대로 **`OtlpTraceReceiver`는 `AnalysisEnvironment`가 소유**(필드 + `start()`에서 `otlpReceiver.start()`, `close()`에서 stop). OTEL 모드일 때 `start(...)` 내부에서 `extraEnv.putAll(otelOtlpEnv)` (BuilderCli가 `otel.otlpEnv(sutId, otlpReceiver.endpoint())`를 만들어 전달하거나, Environment가 `OtelAgent` 참조를 받아 직접) + `options.disableHibernateBatch=true`. getter `otlpReceiver()` 노출.

**backend는 `env.start()` 이후 생성**한다 — `begin()/drain()`이 `sut.logOffset()`을 쓰므로 `sut`가 null이면 NPE. 따라서 `BuilderCli`가 `env.start(...)` 직후:
```java
SqlCaptureBackend sqlCapture = "otel".equals(config.sqlCapture())
        ? new OtelSpanCapture(env.otlpReceiver(), env.sut(), new TraceParent(runId(config)))
        : new LogParserCapture(env.sut());
```
`runId`는 결정적 시드: `config.sutId() + ":" + config.commitSha()`(둘 다 BuildConfig에 존재) — 같은 commit 재분석 시 동일 trace 시퀀스(재현성), 다른 SUT/commit은 충돌 없음. commitSha가 없으면 `sutId`만.

- [ ] **Step 3: 빌더가 backend를 runner에 주입** — `BuilderCli` line 437 `new EndpointExplorationRunner(...)`에 `sqlCapture` 인자 추가. (Kafka는 Task 5.1.)

- [ ] **Step 4: SutOptions 필드 추가 호출부** — `SutOptions`에 `boolean disableHibernateBatch`(기본 false) 추가 시 카노니컬/편의 생성자와 호출부(`AnalysisEnvironment` line 105, `BuilderCli`, 테스트)를 grep `new SutOptions(`로 열거해 갱신. (또는 `SutOptions`에 `withDisableHibernateBatch()` fluent 추가로 생성자 변경 최소화.)

- [ ] **Step 5: 기존 e2e green** → Run: `./gradlew :graph-rag-builder:test` + `./gradlew :e2e:test`.
- [ ] **Step 6: Commit** → `feat(cli,env): --sql-capture flag + Environment-owned OTLP receiver, backend built post-start`

### Task 4.6: 수용-1 (parity) + 수용-2 (동시성 귀속)

**Files:** Test in `e2e` (또는 `BuilderE2eTest`)

- [ ] **Step 1: 수용-1 — petclinic OTEL parity**

petclinic을 `--sql-capture otel`로 분석 → 생성 e2e 45개 green 유지 + 캡처된 SQL bindings가 `log` 모드 산출과 동등함을 비교하는 테스트. (두 모드로 분석 후 그래프의 CapturedSql 집합 비교, 또는 기존 e2e가 두 모드 모두 green.)

Run: `./gradlew :e2e:test`
Expected: PASS, 기존 45 + 신규.

- [ ] **Step 2: 수용-2 — 인터리브 귀속 (구체화 — 리뷰 반영)**

기존 분석 SUT(예: order-service 샘플) 대상으로, 빌더가 **서로 다른 traceparent를 가진 2개 요청을 2개 스레드에서 동시 발행**한다. 각 요청의 `OtelScope.drain()` 결과가 **자신의 trace-id에 속한 SQL만** 포함하고 상대 요청의 SQL이 섞이지 않음을 단언한다(byte-offset 경로가 섞이던 케이스 대비). 동시성 재현을 위해 한 요청은 의도적으로 느린 경로(또는 sleep 유발 입력)로 인터리브를 만든다.

- [ ] **Step 3: Commit** → `test(e2e): OTEL SQL capture parity + concurrent attribution acceptance`

---

## Phase 5 — Kafka 배선 + 수용-3

### Task 5.1: KafkaCaptureRunner scope 배선 (PoC② 결과 반영)

**Files:** Modify `graph-rag-builder/src/main/java/io/graphrag/builder/run/KafkaCaptureRunner.java`

- [ ] **Step 1: 생성자에 `SqlCaptureBackend sqlCapture` 추가** (기존 호출부에 backend 전달).

- [ ] **Step 2: `publishAndCapture` 배선** (line 110~132)

- `long logStart = sut.logOffset();` 다음에 `SqlCaptureBackend.Scope scope = sqlCapture.begin();`.
- `ProducerRecord` 생성 시 `scope.requestHeaders()`를 **레코드 헤더**로 주입:
```java
ProducerRecord<String, String> record =
        new ProducerRecord<>(consumer.topic(), key, Json.mapper().writeValueAsString(payload));
scope.requestHeaders().forEach((k, v) -> {
    record.headers().remove(k);   // 중복 헤더 방지 (리뷰 반영 — add는 누적)
    record.headers().add(k, v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
});
producer.send(record).get();
```
- happy(`awaitSql=true`): `List<ParsedSql> parsed = scope.drain();` (OTEL await; log 모드는 `drain(AWAIT_MILLIS)`).
- variant(`awaitSql=false`): `List<ParsedSql> parsed = scope.drain(VARIANT_SETTLE_MILLIS);` (단축 timeout — early-return은 빈 SQL 기대).
- `captureSql(exchangeId, payload, logSegment)` → `captureSql(exchangeId, payload, parsed)`로 전환(텍스트 파싱 줄 제거, 입력만 교체).

> **PoC②가 link-based였다면**: `OtlpTraceReceiver.awaitEntrySpan`이 trace-id 매칭 외에 "injected traceId로의 link 보유 span"도 보도록 확장(spec 4.1 조건부) + `OtelSpanCapture.toParsedSql`이 link된 trace의 span도 수집. PoC②가 child면 변경 없음.

- [ ] **Step 3: 기존 Kafka 테스트 green** → Run: `./gradlew :graph-rag-builder:test --tests '*Kafka*'`
- [ ] **Step 4: Commit** → `feat(run): wire SqlCaptureBackend into KafkaCaptureRunner (record-header traceparent)`

### Task 5.2: 수용-3 (Kafka OTEL 귀속)

**Files:** Test in e2e (tainted-spring Kafka consumer SUT)

- [ ] **Step 1:** Kafka consumer SUT를 `--with-kafka --sql-capture otel`로 분석 → consumer SQL이 trace-id로 귀속 캡처됨을 검증.
- [ ] **Step 2: Commit** → `test(e2e): Kafka OTEL SQL attribution acceptance`

---

## Phase 6 — attach 배선 + 수용-4

### Task 6.1: 호스트 리시버 도달 (host.docker.internal / extra_hosts)

**Files:** Modify `AttachedComposeEnvironment.java`, `OverrideComposeGenerator.java`, `BuilderCli.runAttached`

- [ ] **Step 1: OverrideComposeGenerator.Spec 확장 (필드 없음 — 리뷰 반영)**

`OverrideComposeGenerator.Spec`(record)에는 현재 `extra_hosts`/OTEL env를 실을 필드가 없다. 추가:
- `boolean addHostGateway`(Linux host-gateway 주입 여부)
- `Map<String,String> extraAppEnv`(OTEL env 주입용) — 또는 기존 env 주입 경로 재사용.
`generate(Spec)`에서 `spec.addHostGateway()`면 app 서비스에 `extra_hosts: ["host.docker.internal:host-gateway"]` 배열 추가, `extraAppEnv`를 app `environment`에 병합. `Spec`의 모든 생성 호출부(BuilderCli line 304~307)에 새 인자 전달.

- [ ] **Step 2: override OTEL env 주입**

`runAttached`(BuilderCli line 287~)에서 OTEL 모드일 때 호스트 `OtlpTraceReceiver`를 띄우고(이 환경이 소유·stop), `Spec.extraAppEnv`에 `otel.otlpEnv(sutId, receiver.hostEndpoint())`를 실어 app 서비스에 주입(`hostEndpoint()` = `http://host.docker.internal:<port>`), `addHostGateway=true`.

- [ ] **Step 3: batch_size=0 병합 (attach)** — `OverrideComposeGenerator`가 app `SPRING_APPLICATION_JSON`을 만들/병합할 때 Task 4.2처럼 batch_size=0을 같은 JSON object에 병합(별도 키로 넣어 치환 충돌 금지).

- [ ] **Step 4: Docker 버전 가드** — host-gateway 미지원(Docker <20.10) 감지 시 경고 로그.

- [ ] **Step 5: 기존 attach 테스트 green** → Run: `./gradlew :graph-rag-builder:test --tests '*Attach*'`
- [ ] **Step 6: Commit** → `feat(env): attach-mode OTLP receiver via host.docker.internal + extra_hosts`

### Task 6.2: 수용-4 (attach docker e2e)

- [ ] **Step 1:** 컨테이너 SUT → 호스트 리시버 SQL 캡처 docker e2e. (CI Docker 20.10+ 확인; 미만이면 컨테이너 네트워크 내 리시버 주소로 대체.)
- [ ] **Step 2: Commit** → `test(e2e): attach-mode OTEL SQL capture acceptance`

---

## Phase 7 — 기본값 OTEL 전환 + 문서

### Task 7.1: 기본값 전환

**Files:** Modify `BuildConfig.java`(`sqlCapture` 기본 `"otel"`) / `BuilderCli`

- [ ] **Step 1:** PoC 게이트 + 수용 1~4 green 확인 후 기본 `otel`. 경로별 PoC 실패가 있으면 그 경로만 `log` 유지(조건부 기본).
- [ ] **Step 2: 전체 회귀** → Run: `./gradlew test` (unit + integration) + `./gradlew :e2e:test`
- [ ] **Step 3: Commit** → `feat: default SQL capture to OTEL after PoC + acceptance green`

### Task 7.2: 문서 갱신

**Files:** Modify `docs/06-test-environment.md`, `docs/26-attach-mode.md`, `docs/27-roadmap-otel-capture-stub-seeding.md`

- [ ] **Step 1:** OTEL backend 동작·`--sql-capture` 플래그·attach 네트워킹·폴백을 docs에 반영. 27의 항목 1을 "완료"로 표시하고 PoC 결과(인덱스 규약·Kafka 상관 방식)를 기록.
- [ ] **Step 2: Commit** → `docs: OTEL SQL capture backend + --sql-capture + attach networking`

---

## PoC 결과 (Phase 1)

- **PoC①** `db.query.parameter.*` 노출: ☑ **예** — 인덱스 규약: ☑ **0-based** (2026-06-18 실측: petclinic 4.0 + agent 2.16.0, `db.system=h2`, jdbc instrumentation `2.16.0-alpha`. INSERT 5-param이 `db.query.parameter.0`~`.4`로 노출; SELECT bind도 `.0`부터). → `PARAM_INDEX_BASE = 0` 확정(draft 값 그대로). HTTP OTEL 경로 **GO**.
- **PoC①** batch_size=0 효과: 단건 INSERT/SELECT는 batch 무관하게 노출됨 확인. batch 완화는 다건 batch insert 대비 예방책으로 유지(미측정).
- **PoC②** Kafka 상관: ☐ **미측정 — Phase 5 착수 직전 실측 예정** (Kafka broker + consumer SUT 셋업 필요; Phase 2~4는 PoC②와 무관하므로 선진행). Phase 5에서 child/link/불가를 확정해 `awaitEntrySpan` 매칭·폴백 결정.
- 확정값을 Task 3.4 `PARAM_INDEX_BASE`(=0), Task 5.1 매칭 전략(보류)에 반영.

## Definition of Done

- [ ] Phase 1 PoC 게이트 결과 기록 + 경로별 go/no-go 확정.
- [ ] 단위 테스트(TraceParent, LogParserCapture, OtlpTraceReceiver, OtelSpanCapture, OtelAgent) green.
- [ ] 수용-1(parity), 수용-2(동시성), 수용-3(Kafka), 수용-4(attach) green — PoC 통과 경로 한정.
- [ ] 전체 회귀(`./gradlew test` + e2e) green.
- [ ] docs/06·26·27 갱신.
- [ ] PR 전: spec-compliance 리뷰 + 코드 품질 리뷰(pr-review-toolkit:code-reviewer) 트리아지 완료.
