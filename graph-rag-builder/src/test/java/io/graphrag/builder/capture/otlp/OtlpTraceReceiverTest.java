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
                "\"SPAN_KIND_CLIENT\"", "insert into owners (first_name) values (?)", "Alice"));
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
        post(otlpJson(tid, "d".repeat(16), injectedSpanId,
                "\"SPAN_KIND_SERVER\"", "select 1", "x"));
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
        post(otlpJson(tid, "a".repeat(16), "b".repeat(16), "\"SPAN_KIND_CLIENT\"", "select 1", "x"));
        assertThat(receiver.spans(tid)).isNotEmpty();
        receiver.remove(tid);
        assertThat(receiver.spans(tid)).isEmpty();
    }

    @Test
    void anyValue_intNormalized() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        String tid = "4".repeat(32);
        String json = ("{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":[{"
                + "\"traceId\":\"%s\",\"spanId\":\"%s\",\"parentSpanId\":\"%s\",\"name\":\"q\",\"kind\":\"SPAN_KIND_CLIENT\",\"startTimeUnixNano\":\"1\","
                + "\"attributes\":[{\"key\":\"db.query.parameter.0\",\"value\":{\"intValue\":\"7\"}}]"
                + "}]}]}]}").formatted(tid, "a".repeat(16), "b".repeat(16));
        post(json);
        assertThat(receiver.spans(tid).get(0).attributes()).containsEntry("db.query.parameter.0", "7");
    }

    @Test
    void addForTest_seedsWithoutHttp() {
        receiver = new OtlpTraceReceiver();
        String tid = "5".repeat(32);
        receiver.addForTest(new SpanRecord(tid, "a".repeat(16), "b".repeat(16),
                "q", "SPAN_KIND_CLIENT", 1, java.util.Map.of("db.query.text", "select 1"), java.util.List.of()));
        assertThat(receiver.spans(tid)).hasSize(1);
    }
}
