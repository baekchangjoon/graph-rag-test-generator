package io.graphrag.builder.capture.otlp;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
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

    private static byte[] otlp(byte[] traceId, byte[] spanId, byte[] parentSpanId, Span.SpanKind kind,
                              String sqlText, AnyValue param0) {
        Span.Builder span = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(traceId))
                .setSpanId(ByteString.copyFrom(spanId))
                .setParentSpanId(ByteString.copyFrom(parentSpanId))
                .setName("q").setKind(kind).setStartTimeUnixNano(1000L)
                .addAttributes(KeyValue.newBuilder().setKey("db.query.text")
                        .setValue(AnyValue.newBuilder().setStringValue(sqlText)).build())
                .addAttributes(KeyValue.newBuilder().setKey("db.query.parameter.0")
                        .setValue(param0).build());
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(span))).build().toByteArray();
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] fill(int len, int value) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private int post(byte[] body) throws Exception {
        return post(body, null);
    }

    private int post(byte[] body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(receiver.endpoint() + "/v1/traces"))
                .header("Content-Type", "application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("x-graphrag-token", token);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private static byte[] sampleBody(byte[] traceId) {
        return otlp(traceId, fill(8, 0x0a), fill(8, 0x0b), Span.SpanKind.SPAN_KIND_CLIENT,
                "select 1", AnyValue.newBuilder().setStringValue("x").build());
    }

    @Test
    void receivesAndIndexesSpansByTrace() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        byte[] traceId = fill(16, 0x01);
        int status = post(otlp(traceId, fill(8, 0x0a), fill(8, 0x0b), Span.SpanKind.SPAN_KIND_CLIENT,
                "insert into owners (first_name) values (?)",
                AnyValue.newBuilder().setStringValue("Alice").build()));
        assertThat(status).isEqualTo(200);
        assertThat(receiver.spans(hexOf(traceId))).hasSize(1);
        assertThat(receiver.spans(hexOf(traceId)).get(0).attributes())
                .containsEntry("db.query.parameter.0", "Alice");
    }

    @Test
    void awaitEntrySpan_returnsWhenChildOfInjectedSpan() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        byte[] traceId = fill(16, 0x02);
        byte[] injectedSpanId = fill(8, 0x0c);
        post(otlp(traceId, fill(8, 0x0d), injectedSpanId, Span.SpanKind.SPAN_KIND_SERVER,
                "select 1", AnyValue.newBuilder().setStringValue("x").build()));
        assertThat(receiver.awaitEntrySpan(hexOf(traceId), hexOf(injectedSpanId), 1000)).isTrue();
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
        byte[] traceId = fill(16, 0x03);
        post(otlp(traceId, fill(8, 0x0a), fill(8, 0x0b), Span.SpanKind.SPAN_KIND_CLIENT,
                "select 1", AnyValue.newBuilder().setStringValue("x").build()));
        assertThat(receiver.spans(hexOf(traceId))).isNotEmpty();
        receiver.remove(hexOf(traceId));
        assertThat(receiver.spans(hexOf(traceId))).isEmpty();
    }

    @Test
    void anyValue_intNormalized() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();
        byte[] traceId = fill(16, 0x04);
        post(otlp(traceId, fill(8, 0x0a), fill(8, 0x0b), Span.SpanKind.SPAN_KIND_CLIENT,
                "select 1", AnyValue.newBuilder().setIntValue(7).build()));
        assertThat(receiver.spans(hexOf(traceId)).get(0).attributes())
                .containsEntry("db.query.parameter.0", "7");
    }

    @Test
    void auth_rejectsMissingOrWrongTokenAndAcceptsCorrect() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start("127.0.0.1", "s3cr3t-token");   // attach 모드: bind + per-run secret
        byte[] traceId = fill(16, 0x06);

        assertThat(post(sampleBody(traceId), null)).isEqualTo(401);          // 헤더 없음
        assertThat(post(sampleBody(traceId), "wrong")).isEqualTo(401);       // 불일치
        assertThat(receiver.spans(hexOf(traceId))).isEmpty();               // 거부된 span은 미기록

        assertThat(post(sampleBody(traceId), "s3cr3t-token")).isEqualTo(200);
        assertThat(receiver.spans(hexOf(traceId))).hasSize(1);
    }

    @Test
    void noAuth_whenStartedWithoutToken() throws Exception {
        receiver = new OtlpTraceReceiver();
        receiver.start();   // analysis 모드: loopback + 무인증
        byte[] traceId = fill(16, 0x07);
        assertThat(post(sampleBody(traceId), null)).isEqualTo(200);
        assertThat(receiver.spans(hexOf(traceId))).hasSize(1);
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
