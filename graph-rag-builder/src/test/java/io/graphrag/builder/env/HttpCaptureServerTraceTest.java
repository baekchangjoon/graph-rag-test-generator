package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.explore.RawHttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class HttpCaptureServerTraceTest {

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private int doGet(String url, String traceparent) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (traceparent != null) {
            b.header("traceparent", traceparent);
        }
        return HttpClient.newHttpClient()
                .send(b.build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    void drainCapturesOutboundTraceIdFromTraceparent() throws Exception {
        server = new HttpCaptureServer(TraceKey.forMode("otel"));
        server.start(null, null);
        StubMapping mapping = get(urlPathEqualTo("/inventory/stock"))
                .willReturn(aResponse().withStatus(200)).build();
        server.registerStub(mapping);

        assertThat(doGet(server.baseUrl() + "/inventory/stock", "00-abc123def-span01-01"))
                .isEqualTo(200);   // registerStub mapping serves 200

        RawHttpExchange ex = server.drainNewExchanges().get(0);
        assertThat(ex.outboundTraceId()).isEqualTo("abc123def");
    }

    @Test
    void drainTraceIdEmptyWhenNoHeader() throws Exception {
        server = new HttpCaptureServer(TraceKey.forMode("otel"));
        server.start(null, null);
        server.registerStub(get(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(200)).build());

        assertThat(doGet(server.baseUrl() + "/x", null)).isEqualTo(200);

        assertThat(server.drainNewExchanges().get(0).outboundTraceId()).isEmpty();
    }
}
