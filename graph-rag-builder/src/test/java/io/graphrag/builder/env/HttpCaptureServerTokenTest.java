package io.graphrag.builder.env;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class HttpCaptureServerTokenTest {
    private HttpCaptureServer server;
    @AfterEach void tearDown() { if (server != null) server.close(); }

    private static Path stubDir() throws Exception {
        Path d = Files.createTempDirectory("stubs");
        Files.writeString(d.resolve("inv.json"), """
            {"request":{"method":"GET","urlPath":"/inventory/stock"},
             "response":{"status":200,"jsonBody":{"available":50}}}""");
        return d;
    }
    private static int get(String url) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test void withToken_rejectsMissingPrefix_servesWithPrefix_drainStripsAndSkips401() throws Exception {
        server = new HttpCaptureServer();
        server.start(stubDir(), "tok123");
        String base = "http://127.0.0.1:" + server.port();
        assertThat(get(base + "/inventory/stock?type=EXPRESS")).isEqualTo(401);          // no token → 401
        assertThat(get(base + "/tok123/inventory/stock?type=EXPRESS")).isEqualTo(200);   // token prefix → stub matches
        var ex = server.drainNewExchanges();
        assertThat(ex).hasSize(1);                                  // 401 probe excluded
        assertThat(ex.get(0).urlPath()).isEqualTo("/inventory/stock");   // token stripped
        assertThat(ex.get(0).status()).isEqualTo(200);
    }

    @Test void hostBaseUrl_includesHostGatewayAndToken() throws Exception {
        server = new HttpCaptureServer();
        server.start(null, "tok123");
        assertThat(server.hostBaseUrl()).isEqualTo("http://host.docker.internal:" + server.port() + "/tok123");
    }

    @Test void noToken_servesDirectly_analysisMode() throws Exception {
        server = new HttpCaptureServer();
        server.start(stubDir(), null);
        assertThat(get("http://127.0.0.1:" + server.port() + "/inventory/stock")).isEqualTo(200);
        assertThat(server.drainNewExchanges().get(0).urlPath()).isEqualTo("/inventory/stock");
    }
}
