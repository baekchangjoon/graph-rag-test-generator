package io.graphrag.builder.run;

import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalStubSynthesizerTest {

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer();
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(Map.of()));
    }

    @Test
    void registerServesSynthesizedBody() throws Exception {
        BodyShape shape = new BodyShape("InventoryResponse",
                List.of(new BodyShape.BodyField("available", "Integer")), false);
        ExternalStubSynthesizer syn = synthesizer();

        assertThat(syn.register("GET", "/inventory/stock", shape)).isTrue();

        HttpResponse<String> resp = get(server.baseUrl() + "/inventory/stock");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(resp.body()).isEqualTo("{\"available\":1}");
    }

    @Test
    void registerIsIdempotentForSameMethodAndPath() {
        BodyShape shape = new BodyShape("InventoryResponse",
                List.of(new BodyShape.BodyField("available", "Integer")), false);
        ExternalStubSynthesizer syn = synthesizer();

        assertThat(syn.register("GET", "/inventory/stock", shape)).isTrue();
        assertThat(syn.register("GET", "/inventory/stock", shape)).isFalse();
    }

    @Test
    void registerPostUsesPostMapping() throws Exception {
        BodyShape shape = new BodyShape("Ack",
                List.of(new BodyShape.BodyField("ok", "Boolean")), false);
        ExternalStubSynthesizer syn = synthesizer();

        assertThat(syn.register("POST", "/orders", shape)).isTrue();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/orders"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("{\"ok\":false}");
    }
}
