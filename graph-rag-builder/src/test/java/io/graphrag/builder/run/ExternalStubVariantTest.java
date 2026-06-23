package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.OtelTraceKey;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변형 stub trace-id 격리 (REQ-008). 같은 (method,path)에 trace-id로 갈리는 변형 stub이 공존하고,
 * 각 요청의 traceparent 헤더로 격리 매칭되며, removeVariant 후 전역(단계1) stub으로 복원된다.
 */
class ExternalStubVariantTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer(new OtelTraceKey());
        server.start(null, null);
        Map<String, List<String>> enums = Map.of(
                "p.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER"));
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(enums), new OtelTraceKey());
    }

    /** traceparent 헤더(otel)로 요청. */
    private HttpResponse<String> get(String url, String traceId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (traceId != null) {
            b.header("traceparent", "00-" + traceId + "-span0000000001-01");
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private com.fasterxml.jackson.databind.JsonNode body(int available, String mode) throws Exception {
        return MAPPER.readTree("{\"available\":" + available + ",\"mode\":\"" + mode + "\"}");
    }

    @Test
    void variantsCoexistAndIsolateByTraceId() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        // 전역(단계1) stub: 첫 상수 STANDARD baseline.
        syn.register("GET", "/inventory/stock",
                new BodyShape("Inv", List.of(
                        new BodyShape.BodyField("available", "Integer"),
                        new BodyShape.BodyField("mode", "p.FulfillmentMode")), false));

        UUID v1 = syn.registerVariant("GET", "/inventory/stock", body(1, "EXPRESS_ONLY"), "trace000000000001");
        UUID v2 = syn.registerVariant("GET", "/inventory/stock", body(1, "BACKORDER"), "trace000000000002");

        // 멱등 차단 없음: 두 변형 모두 별개 UUID로 등록.
        assertThat(v1).isNotNull();
        assertThat(v2).isNotNull();
        assertThat(v1).isNotEqualTo(v2);

        // T1 요청 → 변형1(EXPRESS_ONLY), T2 → 변형2(BACKORDER).
        assertThat(get(server.baseUrl() + "/inventory/stock", "trace000000000001").body())
                .contains("EXPRESS_ONLY");
        assertThat(get(server.baseUrl() + "/inventory/stock", "trace000000000002").body())
                .contains("BACKORDER");
    }

    @Test
    void removeVariantRestoresGlobalStub() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock",
                new BodyShape("Inv", List.of(
                        new BodyShape.BodyField("available", "Integer"),
                        new BodyShape.BodyField("mode", "p.FulfillmentMode")), false));

        UUID v1 = syn.registerVariant("GET", "/inventory/stock", body(1, "EXPRESS_ONLY"), "trace000000000001");
        assertThat(get(server.baseUrl() + "/inventory/stock", "trace000000000001").body())
                .contains("EXPRESS_ONLY");

        syn.removeVariant(v1);

        // 변형 제거 후 동일 trace-id 요청은 전역(단계1, 첫 상수 STANDARD) stub으로 복원.
        HttpResponse<String> resp = get(server.baseUrl() + "/inventory/stock", "trace000000000001");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("STANDARD");
        assertThat(resp.body()).doesNotContain("EXPRESS_ONLY");
    }

    @Test
    void isVariantRegisteredTracksMethodAndPath() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        assertThat(syn.isVariantRegistered("GET", "/inventory/stock")).isFalse();

        UUID v1 = syn.registerVariant("GET", "/inventory/stock", body(1, "EXPRESS_ONLY"), "trace000000000001");
        assertThat(syn.isVariantRegistered("GET", "/inventory/stock")).isTrue();

        syn.removeVariant(v1);
        assertThat(syn.isVariantRegistered("GET", "/inventory/stock")).isFalse();
    }
}
