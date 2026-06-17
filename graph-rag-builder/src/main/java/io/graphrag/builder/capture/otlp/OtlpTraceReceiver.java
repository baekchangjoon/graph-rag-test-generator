package io.graphrag.builder.capture.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.model.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process OTLP/JSON 트레이스 수신기. SUT의 OTEL agent가 POST /v1/traces 로 보내는
 * ExportTraceServiceRequest(JSON)를 디코드해 traceId별로 span을 누적한다. (JDK 내장 HttpServer)
 */
public final class OtlpTraceReceiver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtlpTraceReceiver.class);

    private final Map<String, List<SpanRecord>> byTrace = new ConcurrentHashMap<>();
    private final Map<String, Long> lastArrivalNanos = new ConcurrentHashMap<>();
    private HttpServer server;

    public void start() {
        try {
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

    public String endpoint() {
        return "http://127.0.0.1:" + port();
    }

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

    public void addForTest(SpanRecord span) {
        record(span);
    }

    private void record(SpanRecord span) {
        byTrace.computeIfAbsent(span.traceId(), k -> new CopyOnWriteArrayList<>()).add(span);
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

    private static String anyValueToString(JsonNode value) {
        if (value.has("stringValue")) { return value.path("stringValue").asText(); }
        if (value.has("intValue")) { return value.path("intValue").asText(); }
        if (value.has("doubleValue")) { return value.path("doubleValue").asText(); }
        if (value.has("boolValue")) { return value.path("boolValue").asText(); }
        return value.path("stringValue").asText();
    }

    public List<SpanRecord> spans(String traceId) {
        return List.copyOf(byTrace.getOrDefault(traceId, List.of()));
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
