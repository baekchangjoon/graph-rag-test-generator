package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenProviderTest {

    @Test
    void logsInOnceAndCachesToken() throws Exception {
        int[] hits = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            hits[0]++;
            byte[] body = "{\"token\":\"jwt-abc\",\"type\":\"Bearer\"}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            AuthConfig config = new AuthConfig("/api/auth/login", "admin", "password",
                    "token", "Authorization", "Bearer", List.of());
            AuthTokenProvider provider = new AuthTokenProvider(base, config,
                    io.graphrag.model.RequestHeaders.empty());
            assertThat(provider.token()).isEqualTo("jwt-abc");
            assertThat(provider.token()).isEqualTo("jwt-abc");
            assertThat(hits[0]).isEqualTo(1);
            assertThat(config.headerValue("jwt-abc")).isEqualTo("Bearer jwt-abc");
        } finally {
            server.stop(0);
        }
    }
}
