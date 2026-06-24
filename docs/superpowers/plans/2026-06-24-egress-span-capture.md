# 트레이싱 기반 외부 HTTP egress 발견 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **요구사항명세:** docs/superpowers/requirements/2026-06-24-egress-span-capture-requirements.md
> **설계:** docs/superpowers/specs/2026-06-24-egress-span-capture-design.md

**Goal:** SUT의 outbound base URL을 WireMock으로 리다이렉트하지 않고도, 빌더가 붙인 트레이싱(OTEL javaagent / Sleuth·Brave)의 egress CLIENT span에서 외부 HTTP 호출(method+path)을 발견해 graph `CapturedHttpCall`로 환류한다.

**Architecture:** Zipkin v2 span을 기존 `SpanRecord`로 정규화해, 단일 `EgressNormalizer`가 otel(`OtlpTraceReceiver`, kind=`SPAN_KIND_CLIENT`)와 sleuth(신규 `ZipkinSpanReceiver`, kind=`CLIENT`) 양쪽 span을 `EgressCall`로 환원한다. 요청 scope의 trace-id(`httpCapture.traceKey()`)로 귀속해 `CapturedHttpCall`로 매핑한다.

**Tech Stack:** Java 17(builder toolchain; root build.gradle.kts L53), JUnit5, Testcontainers, `com.sun.net.httpserver`, Jackson(`io.graphrag.model.Json`), 샘플 SUT(legacy-tram/order-web=Sleuth3.1.9·Brave·Java8, order-service=OTEL).

## Global Constraints
- 산출물 경계: **발견 레코드(`CapturedHttpCall`)**까지. redirect 없이 stub 등록은 범위 밖(REQ-015 🔵).
- body 미수집 — `responseBody=""`, `requestBody=null`(REQ-012 🔵).
- 초기 범위: sleuth/otel **analysis-mode loopback(무인증)**. attach-mode 토큰 인증은 REQ-016 🔵.
- 실제 record: `CapturedHttpCall(id, pathId, method, urlPath, query, requestBody, responseStatus:int, responseBody, consumedFields, baggagePropagated:boolean, responseProvenance:Provenance{CAPTURED,SYNTHESIZED})`.
- `SpanRecord(traceId, spanId, parentSpanId, name, kind, startUnixNano:long, attributes:Map, **linkedTraceIds**:List)`.
- traceId 취득: `httpCapture.traceKey().readTraceId(Map outboundHeaders):Optional<String>` (모드 인지: `OtelTraceKey`/`SleuthTraceKey`/`NoTraceKey`).
- **otel 수집 순서(필수):** `OtelSpanCapture.drain()`은 finally에서 `receiver.remove(traceId)`로 버퍼를 비운다 → egress는 **`sqlScope.drain()` 호출 이전**에 수집. (`doSendWithScope` L2005 `sqlScope.drain()` 직전.)
- **analysis 모드 sleuth(필수):** OTEL javaagent 미부착(attach와 대칭; Brave/OTEL 이중계측·`brave.Tracing` 빈 충돌 회피).
- dedup: 한 요청(단일 trace) 내 `(method, urlPath)`, redirect 우선. 교차-trace dedup 아님.
- 테스트 자원 정리(REQ-011): 고유 compose project + `down -v --remove-orphans` + 종료 경로 보장 + 잔존 0 검증. 무차별 정리·공유 인프라 금지.

---

### Task 0: E2E 골격 먼저 작성 (이중 루프 outer-loop, red)
**REQ-IDs:** REQ-009, REQ-010, REQ-011

**Files:**
- Create(skeleton): `graph-rag-builder/src/test/java/io/graphrag/builder/capture/OtelEgressDiscoveryE2E.java`
- Create(skeleton): `graph-rag-builder/src/test/java/io/graphrag/builder/capture/SleuthEgressDiscoveryE2E.java`

- [ ] **Step 1: 두 E2E의 `@Disabled`-아닌 골격 작성(컴파일되되 FAIL)** — Task 9·10의 Step 1 본문(아래)을 미리 작성. 아직 구현(Task 1~8)이 없어 assert가 실패하거나 환경 부재로 red. **약화·주석처리 금지.**
- [ ] **Step 2: red 확인** — Run: `./gradlew :graph-rag-builder:compileTestJava` 통과 + E2E는 `@EnabledIfSystemProperty(sut.jar)` 미충족으로 skip 또는 실행 시 FAIL. 이 red 상태를 기준선으로 둔다.
- [ ] **Step 3: 커밋** — `git commit -am "test(egress): E2E 골격 red 기준선 (REQ-009/010/011)"`

> 이후 Task 1~8을 진행하며 이 E2E들을 green으로 드라이브한다. Task 9·10에서 본문을 완성·검증한다.

---

### Task 1: TraceReceiverLimits 상수 추출
**REQ-IDs:** REQ-006

**Files:**
- Create: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/trace/TraceReceiverLimits.java`
- Modify: `graph-rag-builder/src/main/java/io/graphrag/builder/capture/otlp/OtlpTraceReceiver.java:42-44` (사용처 L148/155/164)
- Test: `graph-rag-builder/src/test/java/io/graphrag/builder/capture/trace/TraceReceiverLimitsTest.java`

**Interfaces:** Produces `TraceReceiverLimits.MAX_TRACES=50_000`, `MAX_SPANS_PER_TRACE=10_000`, `Pattern HEX_32`.

- [ ] **Step 1: 실패 테스트**
```java
package io.graphrag.builder.capture.trace;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class TraceReceiverLimitsTest {
    @Test void exposesSharedLimits() {
        assertThat(TraceReceiverLimits.MAX_TRACES).isEqualTo(50_000);
        assertThat(TraceReceiverLimits.MAX_SPANS_PER_TRACE).isEqualTo(10_000);
        assertThat(TraceReceiverLimits.HEX_32.matcher("a".repeat(32)).matches()).isTrue();
        assertThat(TraceReceiverLimits.HEX_32.matcher("A".repeat(32)).matches()).isFalse();
    }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:compileTestJava` → FAIL.
- [ ] **Step 3: 구현**
```java
package io.graphrag.builder.capture.trace;
import java.util.regex.Pattern;
public final class TraceReceiverLimits {
    private TraceReceiverLimits() {}
    public static final int MAX_TRACES = 50_000;
    public static final int MAX_SPANS_PER_TRACE = 10_000;
    public static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");
}
```
- [ ] **Step 4: OtlpTraceReceiver 치환** — private 상수 3개 제거, 사용처를 `TraceReceiverLimits.*`로. import 추가.
- [ ] **Step 5: 통과 + 회귀** — `./gradlew :graph-rag-builder:test --tests '*TraceReceiverLimitsTest' --tests '*OtlpTraceReceiverTest'` → PASS.
- [ ] **Step 6: 커밋** — `git commit -am "refactor(capture): TraceReceiverLimits 공유 상수 추출 (REQ-006)"`

---

### Task 2: EgressCall + EgressNormalizer
**REQ-IDs:** REQ-001, REQ-002, REQ-003

**Files:**
- Create: `.../capture/egress/EgressCall.java`, `.../capture/egress/EgressNormalizer.java`
- Test: `.../capture/egress/EgressNormalizerTest.java`

**Interfaces:** `record EgressCall(String method, String path, Integer statusOrNull, String traceId, long startNanos)`; `Optional<EgressCall> EgressNormalizer.fromSpan(SpanRecord)`.

- [ ] **Step 1: 실패 테스트** (Task 매트릭스: 이 클래스가 REQ-001/002/003 커버; `@DisplayName`로 REQ 참조)
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressNormalizerTest {
    private static SpanRecord span(String kind, Map<String,String> a){
        return new SpanRecord("a".repeat(32),"b".repeat(16),"c".repeat(16),"post",kind,123L,a,List.of()); }
    @Test @DisplayName("REQ-001/003: otel new semconv, query strip, status null")
    void otelNew(){ var e=EgressNormalizer.fromSpan(span("SPAN_KIND_CLIENT",
        Map.of("http.request.method","GET","url.path","/inventory/stock","url.query","type=X"))).orElseThrow();
        assertThat(e.method()).isEqualTo("GET"); assertThat(e.path()).isEqualTo("/inventory/stock"); assertThat(e.statusOrNull()).isNull(); }
    @Test @DisplayName("REQ-003: otel old semconv url.full fallback + status")
    void otelOld(){ var e=EgressNormalizer.fromSpan(span("SPAN_KIND_CLIENT",
        Map.of("http.method","GET","http.url","http://inventory/stock?type=X","http.status_code","200"))).orElseThrow();
        assertThat(e.path()).isEqualTo("/inventory/stock"); assertThat(e.statusOrNull()).isEqualTo(200); }
    @Test @DisplayName("REQ-002: zipkin CLIENT path-only")
    void zipkin(){ var e=EgressNormalizer.fromSpan(span("CLIENT",
        Map.of("http.method","POST","http.path","/reservations"))).orElseThrow();
        assertThat(e.method()).isEqualTo("POST"); assertThat(e.path()).isEqualTo("/reservations"); }
    @Test @DisplayName("REQ-003: error status tag")
    void err(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",
        Map.of("http.method","POST","http.path","/reservations","http.status_code","500","error","500"))).orElseThrow().statusOrNull()).isEqualTo(500); }
    @Test @DisplayName("REQ-003: non-http CLIENT excluded")
    void nonHttp(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",Map.of("rpc.method","Check")))).isEmpty(); }
    @Test @DisplayName("REQ-001: server span excluded")
    void server(){ assertThat(EgressNormalizer.fromSpan(span("SPAN_KIND_SERVER",Map.of("http.method","GET","http.path","/x")))).isEmpty(); }
    @Test @DisplayName("REQ-002: no path excluded")
    void noPath(){ assertThat(EgressNormalizer.fromSpan(span("CLIENT",Map.of("http.method","GET")))).isEmpty(); }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*EgressNormalizerTest'` → FAIL.
- [ ] **Step 3: EgressCall**
```java
package io.graphrag.builder.capture.egress;
public record EgressCall(String method, String path, Integer statusOrNull, String traceId, long startNanos) {}
```
- [ ] **Step 4: EgressNormalizer** (Task 2 본문 — 이전 버전과 동일 로직; isClient/positive-http/path semconv fallback/status)
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import java.net.URI; import java.util.Map; import java.util.Optional;
public final class EgressNormalizer {
    private EgressNormalizer() {}
    public static Optional<EgressCall> fromSpan(SpanRecord s) {
        if (!isClient(s.kind())) return Optional.empty();
        Map<String,String> a = s.attributes();
        String method = firstNonNull(a.get("http.request.method"), a.get("http.method"));
        if (method == null || method.isBlank()) return Optional.empty();
        String path = extractPath(a);
        if (path == null || path.isBlank()) return Optional.empty();
        Integer status = parseIntOrNull(firstNonNull(a.get("http.response.status_code"), a.get("http.status_code")));
        return Optional.of(new EgressCall(method, path, status, s.traceId(), s.startUnixNano()));
    }
    private static boolean isClient(String k){ return "SPAN_KIND_CLIENT".equals(k) || "CLIENT".equals(k); }
    private static String extractPath(Map<String,String> a){
        String p = firstNonNull(a.get("url.path"), a.get("http.target"), a.get("http.path"));
        if (p != null) return stripQuery(p);
        String full = firstNonNull(a.get("url.full"), a.get("http.url"));
        if (full == null) return null;
        try { return stripQuery(URI.create(full).getPath()); } catch (RuntimeException e) { return stripQuery(full); }
    }
    private static String stripQuery(String s){ if (s==null) return null; int q=s.indexOf('?'); return q<0?s:s.substring(0,q); }
    private static String firstNonNull(String... v){ for (String x:v) if (x!=null) return x; return null; }
    private static Integer parseIntOrNull(String v){ if (v==null||v.isBlank()) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e){ return null; } }
}
```
- [ ] **Step 5: 통과** — `./gradlew :graph-rag-builder:test --tests '*EgressNormalizerTest'` → PASS.
- [ ] **Step 6: 커밋** — `git commit -am "feat(egress): EgressCall + EgressNormalizer (REQ-001/002/003)"`

---

### Task 3: ZipkinSpanReceiver (Java17 호환)
**REQ-IDs:** REQ-002, REQ-006

**Files:** Create `.../capture/zipkin/ZipkinSpanReceiver.java`; Test `.../capture/zipkin/ZipkinSpanReceiverTest.java`

**Interfaces:** `start()`(loopback), `endpoint()`, `hostEndpoint()`, `port()`, `List<SpanRecord> spans(String)`, `boolean isQuiescent(String,long)`, `remove(String)`, `close()`. (attach-mode wildcard+token = REQ-016 🔵, 미구현.)

- [ ] **Step 1: 실패 테스트** (Task 매트릭스: REQ-006; gzip/plain·HEX_32·kind 보존)
```java
package io.graphrag.builder.capture.zipkin;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.net.URI; import java.net.http.*;
import static org.assertj.core.api.Assertions.assertThat;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZipkinSpanReceiverTest {
    private ZipkinSpanReceiver r; private final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void s(){ r=new ZipkinSpanReceiver(); r.start(); }
    @AfterAll void e(){ r.close(); }
    private int post(String b) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(r.endpoint()+"/api/v2/spans"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(b)).build(),
            HttpResponse.BodyHandlers.ofString()).statusCode(); }
    @Test @DisplayName("REQ-006: ingest CLIENT span, micros→nanos")
    void ingest() throws Exception {
        String t="1".repeat(32);
        assertThat(post("[{\"traceId\":\""+t+"\",\"id\":\"2222222222222222\",\"parentId\":\"3333333333333333\","
            +"\"kind\":\"CLIENT\",\"name\":\"post\",\"timestamp\":1700000000000000,"
            +"\"tags\":{\"http.method\":\"POST\",\"http.path\":\"/reservations\"}}]")).isEqualTo(202);
        var spans=r.spans(t); assertThat(spans).hasSize(1);
        assertThat(spans.get(0).kind()).isEqualTo("CLIENT");
        assertThat(spans.get(0).startUnixNano()).isEqualTo(1700000000000000L*1000);
    }
    @Test @DisplayName("REQ-006: reject non-32hex traceId")
    void reject() throws Exception { post("[{\"traceId\":\"XYZ\",\"id\":\"2\",\"kind\":\"CLIENT\",\"tags\":{\"http.method\":\"GET\",\"http.path\":\"/x\"}}]");
        assertThat(r.spans("XYZ")).isEmpty(); }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*ZipkinSpanReceiverTest'` → FAIL.
- [ ] **Step 3: 구현** (Java17 호환: `try/finally`+`ex.close()`(HttpExchange는 AutoCloseable 아님), executor 바인딩, evict는 computeIfAbsent **밖**에서)
```java
package io.graphrag.builder.capture.zipkin;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.otlp.SpanRecord;
import io.graphrag.builder.capture.trace.TraceReceiverLimits;
import io.graphrag.model.Json;
import org.slf4j.*;
import java.io.*; import java.net.*; import java.util.*; import java.util.concurrent.*; import java.util.zip.GZIPInputStream;
public final class ZipkinSpanReceiver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ZipkinSpanReceiver.class);
    private static final int MAX_BODY = 5*1024*1024;
    private HttpServer server;
    private final Map<String,List<SpanRecord>> byTrace = new ConcurrentHashMap<>();
    private final Map<String,Long> lastArrival = new ConcurrentHashMap<>();
    public void start(){ start(null); }
    public void start(String bindHost){
        InetAddress addr=(bindHost==null||bindHost.isBlank())?InetAddress.getLoopbackAddress():resolve(bindHost);
        try {
            server=HttpServer.create(new InetSocketAddress(addr,0),0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/api/v2/spans", ex -> {
                try { ingest(readBody(ex)); ex.sendResponseHeaders(202,-1); }
                catch (Exception e){ log.warn("zipkin ingest failed",e); try { ex.sendResponseHeaders(500,-1);} catch(IOException ignore){} }
                finally { ex.close(); }
            });
            server.start(); log.info("zipkin receiver on {}",endpoint());
        } catch (IOException e){ throw new UncheckedIOException("zipkin receiver start",e); }
    }
    private static byte[] readBody(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        byte[] raw=ex.getRequestBody().readNBytes(MAX_BODY);
        if ("gzip".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Content-Encoding")))
            try (var gz=new GZIPInputStream(new ByteArrayInputStream(raw))){ return gz.readAllBytes(); }
        return raw;
    }
    private void ingest(byte[] body) throws IOException {
        JsonNode arr=Json.mapper().readTree(body); if (!arr.isArray()) return;
        for (JsonNode n:arr) record(toRecord(n));
    }
    private static SpanRecord toRecord(JsonNode n){
        Map<String,String> tags=new LinkedHashMap<>();
        JsonNode t=n.get("tags"); if (t!=null) t.fields().forEachRemaining(e->tags.put(e.getKey(),e.getValue().asText()));
        return new SpanRecord(n.path("traceId").asText(""), n.path("id").asText(""), n.path("parentId").asText(""),
            n.path("name").asText(""), n.path("kind").asText(""), n.path("timestamp").asLong(0)*1000L, tags, List.of());
    }
    private void record(SpanRecord s){
        if (!TraceReceiverLimits.HEX_32.matcher(s.traceId()).matches()) return;
        if (!byTrace.containsKey(s.traceId())) evictIfFull();   // evict는 computeIfAbsent 밖
        List<SpanRecord> spans=byTrace.computeIfAbsent(s.traceId(), k->new CopyOnWriteArrayList<>());
        if (spans.size()>=TraceReceiverLimits.MAX_SPANS_PER_TRACE) return;
        spans.add(s); lastArrival.put(s.traceId(),System.nanoTime());
    }
    private void evictIfFull(){ if (byTrace.size()<TraceReceiverLimits.MAX_TRACES) return;
        lastArrival.entrySet().stream().min(Map.Entry.comparingByValue()).ifPresent(e->remove(e.getKey())); }
    public List<SpanRecord> spans(String t){ return List.copyOf(byTrace.getOrDefault(t,List.of())); }
    public boolean isQuiescent(String t,long ms){ Long l=lastArrival.get(t); return l!=null&&(System.nanoTime()-l)>=ms*1_000_000L; }
    public void remove(String t){ byTrace.remove(t); lastArrival.remove(t); }
    public String endpoint(){ return "http://127.0.0.1:"+port(); }
    public String hostEndpoint(){ return "http://host.docker.internal:"+port(); }
    public int port(){ return server.getAddress().getPort(); }
    public void stop(){ if (server!=null) server.stop(0); }
    @Override public void close(){ stop(); }
    private static InetAddress resolve(String h){ try { return InetAddress.getByName(h);} catch(IOException e){ throw new UncheckedIOException(e);} }
}
```
- [ ] **Step 4: 통과** — `./gradlew :graph-rag-builder:test --tests '*ZipkinSpanReceiverTest'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -am "feat(egress): ZipkinSpanReceiver (Java17 호환, REQ-002/006)"`

---

### Task 4: EgressCollector (귀속 + 실 quiescence)
**REQ-IDs:** REQ-004, REQ-007

**Files:** Create `.../capture/egress/EgressCollector.java`; Test `.../capture/egress/EgressCollectorTest.java`

**Interfaces:** `EgressCollector(Function<String,List<SpanRecord>> spanSource, BiPredicate<String,Long> quiescent, long quiescenceMillis, long awaitMillis)`; `List<EgressCall> collect(String traceId)`.

- [ ] **Step 1: 실패 테스트** (REQ-004 귀속 + REQ-007 실제 await: 지연 도착 span을 폴링으로 수집)
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.junit.jupiter.api.*;
import java.util.*; import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressCollectorTest {
    private static SpanRecord client(String t,String p){ return new SpanRecord(t,"b".repeat(16),"c".repeat(16),"post","CLIENT",1L,Map.of("http.method","GET","http.path",p),List.of()); }
    @Test @DisplayName("REQ-004: collects only own trace")
    void ownTrace(){ String mine="a".repeat(32),other="f".repeat(32);
        Map<String,List<SpanRecord>> store=Map.of(mine,List.of(client(mine,"/a")),other,List.of(client(other,"/b")));
        var c=new EgressCollector(store::get,(t,q)->true,0,0);
        assertThat(c.collect(mine)).extracting(EgressCall::path).containsExactly("/a"); }
    @Test @DisplayName("REQ-007: awaits late-arriving span until quiescent")
    void awaitsLate() throws Exception {
        String t="a".repeat(32);
        Map<String,List<SpanRecord>> store=new ConcurrentHashMap<>();
        long[] arrived={0};
        java.util.function.BiPredicate<String,Long> quiescent=(tr,q)-> arrived[0]!=0 && (System.nanoTime()-arrived[0])>=q*1_000_000L;
        var c=new EgressCollector(store::get,quiescent,50,3000);
        new Thread(()->{ try{Thread.sleep(200);}catch(InterruptedException ignored){}
            store.put(t,List.of(client(t,"/late"))); arrived[0]=System.nanoTime(); }).start();
        assertThat(c.collect(t)).extracting(EgressCall::path).containsExactly("/late"); }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*EgressCollectorTest'` → FAIL.
- [ ] **Step 3: 구현** (이전 버전 로직 동일 — await 폴링 → 정렬 → normalize)
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.builder.capture.otlp.SpanRecord;
import org.slf4j.*;
import java.util.*; import java.util.function.*;
public final class EgressCollector {
    private static final Logger log=LoggerFactory.getLogger(EgressCollector.class);
    private static final long POLL=50;
    private final Function<String,List<SpanRecord>> spanSource;
    private final BiPredicate<String,Long> quiescent; private final long quiescenceMillis, awaitMillis;
    public EgressCollector(Function<String,List<SpanRecord>> s, BiPredicate<String,Long> q, long qm, long am){
        spanSource=s; quiescent=q; quiescenceMillis=qm; awaitMillis=am; }
    public List<EgressCall> collect(String traceId){
        if (traceId==null) return List.of();
        long deadline=System.nanoTime()+awaitMillis*1_000_000L;
        while (awaitMillis>0 && System.nanoTime()<deadline && !quiescent.test(traceId,quiescenceMillis)) sleep(POLL);
        if (awaitMillis>0 && !quiescent.test(traceId,quiescenceMillis))
            log.warn("egress collect: quiescence not reached for trace {} within {}ms", traceId, awaitMillis);
        List<SpanRecord> spans=spanSource.apply(traceId); if (spans==null) return List.of();
        List<EgressCall> out=new ArrayList<>();
        spans.stream().sorted(Comparator.comparingLong(SpanRecord::startUnixNano))
             .forEach(s->EgressNormalizer.fromSpan(s).ifPresent(out::add));
        return out;
    }
    private static void sleep(long ms){ try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();} }
}
```
- [ ] **Step 4: 통과** — `./gradlew :graph-rag-builder:test --tests '*EgressCollectorTest'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -am "feat(egress): EgressCollector 귀속+quiescence (REQ-004/007)"`

---

### Task 5: EgressCallMapper (환류·dedup·null-safety)
**REQ-IDs:** REQ-005

**Files:** Create `.../capture/egress/EgressCallMapper.java`; Modify `EndpointExplorationRunner.java` `consumedFields`(L1920 부근); Test `.../capture/egress/EgressCallMapperTest.java`

**Interfaces:** `CapturedHttpCall EgressCallMapper.toCapturedHttpCall(EgressCall, String pathId, int seq)`; `List<CapturedHttpCall> EgressCallMapper.mergeDedup(List<CapturedHttpCall> existing, List<CapturedHttpCall> egress)` (한 요청 단위, key=(method,urlPath), existing 우선).

- [ ] **Step 1: 실패 테스트**
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressCallMapperTest {
    @Test @DisplayName("REQ-005: defaults & real field names")
    void defaults(){ var c=EgressCallMapper.toCapturedHttpCall(new EgressCall("GET","/inventory/stock",null,"t",1L),"p1",1);
        assertThat(c.method()).isEqualTo("GET"); assertThat(c.urlPath()).isEqualTo("/inventory/stock");
        assertThat(c.responseStatus()).isEqualTo(200); assertThat(c.responseBody()).isEqualTo("");
        assertThat(c.requestBody()).isNull(); assertThat(c.query()).isEmpty();
        assertThat(c.baggagePropagated()).isFalse(); assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(c.consumedFields()).isEmpty(); }
    @Test @DisplayName("REQ-005: error status kept")
    void err(){ assertThat(EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/r",500,"t",1L),"p",1).responseStatus()).isEqualTo(500); }
    @Test @DisplayName("REQ-005: per-request dedup by (method,urlPath), redirect first")
    void dedup(){ var redirect=EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/reservations",202,"t",1L),"p",1);
        var span=EgressCallMapper.toCapturedHttpCall(new EgressCall("POST","/reservations",null,"t",2L),"p",2);
        var merged=EgressCallMapper.mergeDedup(List.of(redirect),List.of(span));
        assertThat(merged).hasSize(1); assertThat(merged.get(0).responseStatus()).isEqualTo(202); }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*EgressCallMapperTest'` → FAIL.
- [ ] **Step 3: 구현**
```java
package io.graphrag.builder.capture.egress;
import io.graphrag.model.CapturedHttpCall;
import java.util.*;
public final class EgressCallMapper {
    private EgressCallMapper() {}
    public static CapturedHttpCall toCapturedHttpCall(EgressCall e, String pathId, int seq){
        return new CapturedHttpCall("http-"+pathId+"-egress-"+seq, pathId, e.method(), e.path(),
            Map.of(), null, e.statusOrNull()==null?200:e.statusOrNull(), "",
            List.of(), false, CapturedHttpCall.Provenance.CAPTURED);
    }
    /** 한 요청(단일 trace) 내 (method,urlPath) dedup, existing(redirect) 우선. 교차-trace 아님. */
    public static List<CapturedHttpCall> mergeDedup(List<CapturedHttpCall> existing, List<CapturedHttpCall> egress){
        Set<String> seen=new HashSet<>(); List<CapturedHttpCall> out=new ArrayList<>();
        for (CapturedHttpCall c:existing){ seen.add(c.method()+" "+c.urlPath()); out.add(c); }
        for (CapturedHttpCall c:egress) if (seen.add(c.method()+" "+c.urlPath())) out.add(c);
        return out;
    }
}
```
- [ ] **Step 4: consumedFields 방어 가드(가독성)** — `EndpointExplorationRunner.consumedFields(String responseBody)` 진입부에 `if (responseBody==null||responseBody.isBlank()) return List.of();` 추가. (기존 try/catch가 이미 예외를 흡수하나, 빈 입력 파싱 회피·의도 명시.)
- [ ] **Step 5: 통과** — `./gradlew :graph-rag-builder:test --tests '*EgressCallMapperTest'` → PASS.
- [ ] **Step 6: 커밋** — `git commit -am "feat(egress): EgressCallMapper 환류·dedup·null-safety (REQ-005)"`

---

### Task 6: 탐색 루프 연동 (수집 BEFORE drain, traceKey, egressCalls 스레딩)
**REQ-IDs:** REQ-004, REQ-005

**Files:**
- Modify: `InvocationOutcome.java`(egressCalls 필드 추가), `EndpointExplorationRunner.java`(`doSendWithScope` 수집·`PathCandidate`·`captureHttpCalls`), `PathCandidate.java`(egressCalls 필드)
- Test: `.../run/EgressDiscoveryWiringTest.java`

**Interfaces:** `captureHttpCalls(PathCandidate)`가 `candidate.egressCalls()`(List<EgressCall>)를 매핑·dedup 병합. EgressCollector는 runner에 주입(생성자 선택 인자, null이면 비활성).

- [ ] **Step 1: 실패 테스트** (reflection로 private `captureHttpCalls` 호출 — 기존 `ExternalStubLoudFailTest` 패턴 준용)
```java
package io.graphrag.builder.run;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.*;
import java.lang.reflect.Method; import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class EgressDiscoveryWiringTest {
    @Test @DisplayName("REQ-005: captureHttpCalls merges egress (dedup vs redirect)")
    void mergesEgress() throws Exception {
        // PathCandidate를 redirect exchange 1건(POST /reservations) + egressCalls 2건(POST /reservations dup, GET /x)로 구성.
        // (생성 헬퍼는 기존 테스트 픽스처/패키지-프라이빗 빌더 사용.)
        PathCandidate candidate = TestCandidates.withRedirectAndEgress(
            /*redirect*/ "POST","/reservations",
            /*egress*/ List.of(new EgressCall("POST","/reservations",null,"t",1L), new EgressCall("GET","/x",200,"t",2L)));
        EndpointExplorationRunner runner = TestCandidates.runnerWithoutCollector();
        Method m = EndpointExplorationRunner.class.getDeclaredMethod("captureHttpCalls", PathCandidate.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") List<CapturedHttpCall> calls=(List<CapturedHttpCall>) m.invoke(runner, candidate);
        assertThat(calls).extracting(c->c.method()+" "+c.urlPath())
            .containsExactlyInAnyOrder("POST /reservations","GET /x");   // dup 제거됨
    }
}
```
(주: `TestCandidates`는 이 태스크에서 함께 만드는 테스트 헬퍼 — `PathCandidate`/runner 생성의 기존 패키지-프라이빗 경로를 감싼다. 정확한 생성 시그니처는 `PathCandidate`/`EndpointExplorationRunner` 현 구조에 맞춰 작성.)
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*EgressDiscoveryWiringTest'` → FAIL.
- [ ] **Step 3: 구현**
  1. `InvocationOutcome`에 `List<EgressCall> egressCalls`(기본 빈 리스트) 추가. `PathCandidate`에 `List<EgressCall> egressCalls` 추가(outcome→candidate 전달 경로 L108-121 부근에서 복사).
  2. `doSendWithScope`: `http.send()` 직후, **`sqlScope.drain()` 이전**에:
     ```java
     String egressTraceId = httpCapture.traceKey().readTraceId(sqlScope.requestHeaders()).orElse(null);
     List<EgressCall> egressCalls = (egressCollector != null)
         ? egressCollector.collect(egressTraceId) : List.of();
     if (egressTraceId != null && zipkinReceiver != null) /* sleuth */ ; // remove는 collect 후 wiring(Task 7)에서
     ```
     수집 결과를 `InvocationOutcome`에 실어 반환. (otel은 이후 `sqlScope.drain()`이 receiver.remove 수행 — 그 전에 수집했으므로 안전.)
  3. `captureHttpCalls(PathCandidate candidate)` 말미: 기존 RawHttpExchange→CapturedHttpCall 목록(`calls`)에 더해
     ```java
     List<CapturedHttpCall> egress = new ArrayList<>();
     int seq = 0;
     for (EgressCall e : candidate.egressCalls()) egress.add(EgressCallMapper.toCapturedHttpCall(e, candidate.pathId(), ++seq));
     return EgressCallMapper.mergeDedup(calls, egress);
     ```
  4. `EndpointExplorationRunner` 생성자에 `EgressCollector egressCollector`(nullable) 추가. 기존 호출처는 `null` 전달(Task 8에서 실제 주입).
- [ ] **Step 4: 통과 + 회귀** — `./gradlew :graph-rag-builder:test --tests '*EgressDiscoveryWiringTest' --tests '*EndpointExploration*'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -am "feat(egress): 수집 BEFORE drain + egressCalls 스레딩 + 병합 (REQ-004/005)"`

---

### Task 7: ZipkinSpanReceiver 수명주기 + env 배선 + analysis-sleuth OTEL agent 제외
**REQ-IDs:** REQ-006, REQ-008

**Files:**
- Modify: `ExplorationEnvironment.java`(`zipkinReceiver()` default), `AnalysisEnvironment.java`(receiver 소유·기동·`sleuthZipkinEnv`·close), `BuilderCli.java`(analysis-sleuth에서 otel agent 제외 + sleuth env 병합)
- Test: `.../env/ZipkinSutEnvInjectionTest.java`

**Interfaces:** `ExplorationEnvironment.zipkinReceiver()` default `null`; `AnalysisEnvironment` override 반환. `static Map<String,String> AnalysisEnvironment.sleuthZipkinEnv(ZipkinSpanReceiver)`.

- [ ] **Step 1: 실패 테스트**
```java
// ZipkinSutEnvInjectionTest
@Test @DisplayName("REQ-008/006: sleuth env has zipkin endpoint, no sampler override")
void env(){
    var r = new io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver(); r.start();
    try {
        Map<String,String> env = AnalysisEnvironment.sleuthZipkinEnv(r);
        assertThat(env).containsEntry("SPRING_ZIPKIN_SENDER_TYPE","web");
        assertThat(env.get("SPRING_ZIPKIN_BASEURL")).isEqualTo(r.endpoint());
        assertThat(env).doesNotContainKey("SPRING_SLEUTH_SAMPLER_PROBABILITY");
    } finally { r.close(); }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*ZipkinSutEnvInjectionTest'` → FAIL.
- [ ] **Step 3: 구현**
  1. `ExplorationEnvironment`에 `default ZipkinSpanReceiver zipkinReceiver(){ return null; }` 추가.
  2. `AnalysisEnvironment`: sleuth 모드일 때 `ZipkinSpanReceiver` 필드 생성·`start()`(loopback), `zipkinReceiver()` override, `close()`에서 `receiver.close()`. `static Map<String,String> sleuthZipkinEnv(ZipkinSpanReceiver r)` = `{SPRING_ZIPKIN_BASEURL=r.endpoint(), SPRING_ZIPKIN_SENDER_TYPE="web"}`(샘플러 override 없음). 이 env를 SUT 기동 env에 merge.
  3. `BuilderCli` analysis 경로(L260-264 부근): `sleuthMode`이면 SUT `javaToolOptions`에서 **`otel.javaToolOptions()` 제외**(jacoco만), 그리고 `sleuthZipkinEnv` 병합. (attach 경로 L417-419의 sleuth 분기와 대칭.)
- [ ] **Step 4: 통과** — `./gradlew :graph-rag-builder:test --tests '*ZipkinSutEnvInjectionTest'` → PASS.
- [ ] **Step 5: 커밋** — `git commit -am "feat(egress): sleuth ZipkinSpanReceiver 수명주기·env·analysis otel-agent 제외 (REQ-006/008)"`

---

### Task 8: 모드별 EgressCollector 조립 + runner 주입
**REQ-IDs:** REQ-004, REQ-007

**Files:** Modify `BuilderCli.java`/`explore()` 경로(runner 생성), `EndpointExplorationRunner` 생성자; Test `.../run/EgressCollectorWiringTest.java`

**Interfaces:** `static EgressCollector EgressCollector.forMode(ExplorationEnvironment env)`:
- otel: `new EgressCollector(env.otlpReceiver()::spans, env.otlpReceiver()::isQuiescent, 150, 8000)` (OtlpTraceReceiver에 `isQuiescent` 존재).
- sleuth: `new EgressCollector(env.zipkinReceiver()::spans, env.zipkinReceiver()::isQuiescent, ZIPKIN_QUIESCENCE_MILLIS, ZIPKIN_AWAIT_MILLIS)`.
- 둘 다 null이면 `null`(egress 비활성).
- 상수 `ZIPKIN_QUIESCENCE_MILLIS=1200`, `ZIPKIN_AWAIT_MILLIS=3000` (AsyncReporter flush ~1s 기준 시작값; E2E에서 실측·조정).

- [ ] **Step 1: 실패 테스트** — `EgressCollector.forMode`가 otel env면 otlp source, sleuth env면 zipkin source로 만든 collector를 반환(가짜 env로 분기 검증; collector가 non-null).
- [ ] **Step 2: 실패 확인** — `./gradlew :graph-rag-builder:test --tests '*EgressCollectorWiringTest'` → FAIL.
- [ ] **Step 3: 구현** — `EgressCollector.forMode(env)` 팩토리. `explore()`에서 `EgressCollector.forMode(environment)`를 만들어 `EndpointExplorationRunner` 생성자에 전달(Task 6의 nullable 인자). otel `isQuiescent`가 OtlpTraceReceiver에 없으면 추가(이미 있음 — L236).
- [ ] **Step 4: 통과 + 회귀** — `./gradlew :graph-rag-builder:test` → PASS.
- [ ] **Step 5: 커밋** — `git commit -am "feat(egress): 모드별 EgressCollector 조립·주입 (REQ-004/007)"`

---

### Task 9: [E2E] otel redirect-비의존 발견
**REQ-IDs:** REQ-009, REQ-008, REQ-011 (Task 0에서 골격 작성, 여기서 완성·green)

**Files:** `.../capture/OtelEgressDiscoveryE2E.java` (+ 호스트 inventory stub)

- [ ] **Step 1: E2E 본문** — `@Tag("integration")` `@EnabledIfSystemProperty(named="sut.jar", matches=".+")`. order-service를 OTEL 모드로 기동. 호스트 inventory stub: `GET /inventory/stock` → **HTTP 200, body `{"available":5,"mode":"EXPRESS"}`**(InventoryResponse{available, mode} 형상 — 역직렬화 성공 보장). `EXTERNAL_INVENTORY_URL`=그 stub(WireMock 치환 아님). `POST /api/orders`(`type=EXPRESS`로 InventoryClient.check 유발) 탐색 → graph `CapturedHttpCall`에 `(GET, /inventory/stock)` 존재 + `responseProvenance==CAPTURED` assert. redirect/`--external-stubs` 미사용.
- [ ] **Step 2: 자원 정리(REQ-011)** — 고유 compose project(`egress-otel-e2e-<rand>`); `@AfterAll` try/finally로 `docker compose -p <uniq> down -v --remove-orphans`; 호스트 stub은 PID 캡처 후 그 PID만 종료; `@AfterAll` 말미 `label=com.docker.compose.project=<uniq>` 잔존 0 assert.
- [ ] **Step 3: green 확인** — Run(샘플 jar 준비): `./gradlew :graph-rag-builder:test --tests '*OtelEgressDiscoveryE2E' -Dsut.jar=...` → PASS. quiescence flaky 시 Task 8 상수 조정.
- [ ] **Step 4: 매트릭스 🟡→🟢 갱신 + 커밋** — `git commit -am "test(egress): E2E otel redirect-비의존 발견 (REQ-009/008/011)"`

---

### Task 10: [E2E] sleuth/Brave redirect-비의존 발견
**REQ-IDs:** REQ-010, REQ-008, REQ-011 (Task 0에서 골격, 여기서 완성·green)

**Files:** `.../capture/SleuthEgressDiscoveryE2E.java` (+ 호스트 reservation stub)

- [ ] **Step 1: E2E 본문** — order-web을 빌더 sleuth egress 캡처로 analysis 기동(OTEL agent 제외·ZipkinSpanReceiver). 호스트 reservation stub: `POST /reservations` → **HTTP 202, empty body**. `RESERVATION_URL`=그 stub. `POST /orders`(B3 주입) → graph `CapturedHttpCall`에 `(POST, /reservations)` 귀속 + `ZipkinSpanReceiver.spans(traceId)`에 CLIENT span 존재 assert. redirect 미사용.
- [ ] **Step 2: 샘플러(REQ-008) `#samplerOffStillExports`** — SUT를 `SPRING_SLEUTH_SAMPLER_PROBABILITY=0.0`으로 띄워도 주입 `X-B3-Sampled:1`만으로 `(POST,/reservations)` 발견됨 assert.
- [ ] **Step 3: 자원 정리(REQ-011)** — Task 9 Step 2와 동일 패턴(고유 project, `down -v --remove-orphans`, PID 한정, 잔존 0 assert).
- [ ] **Step 4: green 확인** — Run: `./gradlew :graph-rag-builder:test --tests '*SleuthEgressDiscoveryE2E' -Dsut...` → PASS. flush 지연 flaky 시 `ZIPKIN_AWAIT_MILLIS` 상향(실측값 FINDINGS 보강).
- [ ] **Step 5: 매트릭스 🟡→🟢 + 커밋** — `git commit -am "test(egress): E2E sleuth redirect-비의존 발견+샘플러 (REQ-010/008/011)"`

---

## Self-Review
- **Spec coverage:** REQ-001(T2)·002(T2/T3)·003(T2)·004(T4/T6/T8)·005(T5/T6)·006(T1/T3/T7)·007(T4/T8)·008(T7/T10)·009(T9)·010(T10)·011(T0/T9/T10). 🔵 REQ-012~016 태스크 없음(의도). 누락 없음.
- **테스트명↔매트릭스 정합:** 매트릭스를 plan 실제 클래스명으로 갱신해야 함 — REQ-001/002/003→`EgressNormalizerTest`, REQ-004/007→`EgressCollectorTest`, REQ-005→`EgressCallMapperTest`(+`EgressDiscoveryWiringTest`), REQ-006→`ZipkinSpanReceiverTest`(+`TraceReceiverLimitsTest`), REQ-008→`ZipkinSutEnvInjectionTest`+`SleuthEgressDiscoveryE2E`. (요구사항명세 매트릭스를 이 이름으로 동기화.)
- **Java 17:** `try(ex)` 미사용(try/finally+close), virtual-thread 미사용(cachedThreadPool).
- **otel 타이밍:** 수집을 `sqlScope.drain()`(remove 유발) **이전**에. sleuth는 ZipkinSpanReceiver 독립.
- **Type consistency:** `CapturedHttpCall(...,int responseStatus,...,boolean baggagePropagated, Provenance responseProvenance)`, `SpanRecord(...,List linkedTraceIds)`, `httpCapture.traceKey().readTraceId(Map)`, `EgressCall(method,path,Integer statusOrNull,traceId,long startNanos)` — 전 태스크 일치.
- **이중 루프:** Task 0이 E2E 골격을 먼저 red로 둠. Task 1~8이 green 드라이브. Task 9·10 완성.
- **Placeholder:** T1~T5 완전 코드. T6~T8은 대형 파일 수정 — 정확 seam(파일·라인·시그니처·삽입 코드)과 reflection 테스트 패턴 명시. T9·T10은 기존 `OtelHttpCaptureIntegrationTest` 하니스 준용 + stub 응답 body 명시.
