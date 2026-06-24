package io.graphrag.builder.capture.otlp;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.capture.trace.TraceReceiverLimits;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process OTLP/protobuf 트레이스 수신기. SUT의 OTEL agent가 POST /v1/traces 로 보내는
 * ExportTraceServiceRequest(protobuf)를 디코드해 traceId별로 span을 누적한다. (JDK 내장 HttpServer)
 * agent의 OTLP exporter는 http/json을 지원하지 않으므로 http/protobuf 와이어를 사용한다.
 *
 * 신뢰 경계: 빌더가 분석 런 동안만 띄우고 자신이 기동한 SUT의 span만 받는 단기 서버다.
 * loopback에만 바인드해 다른 로컬 프로세스의 span 주입을 막고, 오동작/악성 입력 대비로
 * body 크기·trace/span 수·id 형식을 제한한다. (attach 모드에서 컨테이너 도달이 필요해지면
 * Phase 6에서 bridge-reachable 바인드 + per-run shared-secret 헤더 인증을 추가한다.)
 */
public final class OtlpTraceReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpTraceReceiver.class);

    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;   // SUT 오동작 시 빌더 OOM 방지

    /** 인증 헤더 이름 (attach 모드 per-run shared secret). */
    public static final String AUTH_HEADER = "x-graphrag-token";

    private final Map<String, List<SpanRecord>> byTrace = new ConcurrentHashMap<>();
    private final Map<String, Long> lastArrivalNanos = new ConcurrentHashMap<>();
    private HttpServer server;
    private byte[] authToken;   // null이면 무인증(loopback analysis 모드)

    /** analysis 모드: loopback 전용 바인드 + 무인증(호스트 로컬만 도달 가능). */
    public void start() {
        start(null, null);
    }

    /**
     * @param bindHost  바인드 주소. null/blank면 loopback. attach 모드는 컨테이너가 host-gateway로
     *                  도달해야 하므로 {@code "0.0.0.0"}(wildcard)로 넓힌다.
     * @param authToken null이 아니면 모든 POST에 {@code x-graphrag-token: <authToken>}를 요구(불일치 401).
     *                  wildcard 바인드로 넓어진 노출을 per-run shared secret으로 보상한다.
     */
    public void start(String bindHost, String authToken) {
        this.authToken = authToken == null ? null : authToken.getBytes(StandardCharsets.UTF_8);
        InetAddress addr = (bindHost == null || bindHost.isBlank())
                ? InetAddress.getLoopbackAddress()
                : resolve(bindHost);
        try {
            server = HttpServer.create(new InetSocketAddress(addr, 0), 0);
            server.createContext("/v1/traces", exchange -> {
                try {
                    if (!authorized(exchange)) {
                        log.warn("otlp request rejected: missing/invalid {} header", AUTH_HEADER);
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                    if (body.length > MAX_BODY_BYTES) {
                        log.warn("otlp body exceeds {} bytes — rejected", MAX_BODY_BYTES);
                        exchange.sendResponseHeaders(413, -1);
                        return;
                    }
                    ingest(body);
                    exchange.sendResponseHeaders(200, 0);
                } catch (Exception e) {
                    log.warn("otlp ingest failed", e);
                    exchange.sendResponseHeaders(500, 0);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            log.info("otlp receiver on {} (auth={})", endpoint(), authToken != null);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start otlp receiver", e);
        }
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (IOException e) {
            throw new UncheckedIOException("invalid otlp bind host: " + host, e);
        }
    }

    /** authToken 미설정이면 항상 허용. 설정 시 헤더 값이 constant-time 비교로 일치해야 한다. */
    private boolean authorized(com.sun.net.httpserver.HttpExchange exchange) {
        if (authToken == null) {
            return true;
        }
        String provided = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        return provided != null
                && java.security.MessageDigest.isEqual(authToken, provided.getBytes(StandardCharsets.UTF_8));
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port();
    }

    public String hostEndpoint() {
        return "http://host.docker.internal:" + port();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void ingest(byte[] body) throws Exception {
        ExportTraceServiceRequest req = ExportTraceServiceRequest.parseFrom(body);
        for (ResourceSpans rs : req.getResourceSpansList()) {
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                for (Span span : ss.getSpansList()) {
                    record(toRecord(span));
                }
            }
        }
    }

    public void addForTest(SpanRecord span) {
        record(span);
    }

    private void record(SpanRecord span) {
        // traceId는 맵 키 — W3C 32-hex가 아니면 무시(비정상 키로 인한 상태 폭주/주입 방어).
        if (!TraceReceiverLimits.HEX_32.matcher(span.traceId()).matches()) {
            return;
        }
        List<SpanRecord> spans = byTrace.computeIfAbsent(span.traceId(), k -> {
            evictOldestIfFull();
            return new CopyOnWriteArrayList<>();
        });
        if (spans.size() >= TraceReceiverLimits.MAX_SPANS_PER_TRACE) {
            return;   // 한 trace의 span 폭주 상한
        }
        spans.add(span);
        lastArrivalNanos.put(span.traceId(), System.nanoTime());
    }

    /** trace 수가 상한을 넘으면 가장 오래된(마지막 도착이 가장 이른) trace를 축출. */
    private void evictOldestIfFull() {
        if (byTrace.size() < TraceReceiverLimits.MAX_TRACES) {
            return;
        }
        lastArrivalNanos.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .ifPresent(oldest -> remove(oldest.getKey()));
    }

    private static SpanRecord toRecord(Span span) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (KeyValue kv : span.getAttributesList()) {
            attrs.put(kv.getKey(), anyValueToString(kv.getValue()));
        }
        List<String> linkedTraces = new ArrayList<>();
        for (Span.Link link : span.getLinksList()) {
            linkedTraces.add(hex(link.getTraceId()));
        }
        return new SpanRecord(
                hex(span.getTraceId()),
                hex(span.getSpanId()),
                hex(span.getParentSpanId()),
                span.getName(),
                span.getKind().name(),
                span.getStartTimeUnixNano(),
                attrs,
                linkedTraces);
    }

    private static String hex(ByteString bytes) {
        StringBuilder sb = new StringBuilder(bytes.size() * 2);
        for (byte b : bytes.toByteArray()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String anyValueToString(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INT_VALUE -> Long.toString(v.getIntValue());
            case DOUBLE_VALUE -> Double.toString(v.getDoubleValue());
            case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
            default -> "";
        };
    }

    public List<SpanRecord> spans(String traceId) {
        return List.copyOf(byTrace.getOrDefault(traceId, List.of()));
    }

    /**
     * 진단용: 현재 누적된 모든 trace의 불변 스냅샷(traceId → spans).
     * Kafka 상관 방식(child vs link) 실측·동시성 귀속 검증에 쓴다(읽기 전용 복사본).
     */
    public Map<String, List<SpanRecord>> snapshot() {
        Map<String, List<SpanRecord>> copy = new LinkedHashMap<>();
        byTrace.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

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
