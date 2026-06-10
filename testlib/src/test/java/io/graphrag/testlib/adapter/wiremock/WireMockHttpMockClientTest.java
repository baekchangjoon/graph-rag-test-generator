package io.graphrag.testlib.adapter.wiremock;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.spi.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WireMockHttpMockClientTest {

    record AdminCall(String path, String body) {
    }

    private HttpServer adminStub;
    private final List<AdminCall> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startAdminStub() throws IOException {
        adminStub = HttpServer.create(new InetSocketAddress(0), 0);
        adminStub.createContext("/__admin", exchange -> {
            calls.add(new AdminCall(exchange.getRequestURI().getPath(),
                    new String(exchange.getRequestBody().readAllBytes())));
            byte[] response = "{}".getBytes();
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/mappings") ? 201 : 200,
                    response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        adminStub.start();
    }

    @AfterEach
    void stopAdminStub() {
        adminStub.stop(0);
    }

    private HttpMockClient client(String testId) {
        Env env = Env.of(Map.of("HTTP_MOCK_ADMIN",
                "http://localhost:" + adminStub.getAddress().getPort() + "/__admin"));
        return new WireMockHttpMockAdapter().create(env, testId);
    }

    @Test
    void register_postsWireMockMappingWithBaggageAndMetadata() {
        client("t-abc").stub("GET", "/inventory/stock")
                .withQueryParam("type", "EXPRESS")
                .withBaggageTestId("t-abc")
                .respondJson(200, "{\"available\":50}")
                .register();

        assertThat(calls).hasSize(1);
        AdminCall call = calls.get(0);
        assertThat(call.path()).isEqualTo("/__admin/mappings");
        assertThat(call.body())
                .contains("\"method\":\"GET\"")
                .contains("\"urlPath\":\"/inventory/stock\"")
                .contains("\"type\":{\"equalTo\":\"EXPRESS\"}")
                .contains("\"baggage\":{\"contains\":\"test-id=t-abc\"}")
                .contains("\"graphragTestId\":\"t-abc\"")
                .contains("\"available\\\":50");
    }

    @Test
    void removeAllForScope_removesByMetadata() {
        client("t-abc").removeAllForScope("t-abc");

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path()).isEqualTo("/__admin/mappings/remove-by-metadata");
        assertThat(calls.get(0).body()).contains("graphragTestId").contains("t-abc");
    }
}
