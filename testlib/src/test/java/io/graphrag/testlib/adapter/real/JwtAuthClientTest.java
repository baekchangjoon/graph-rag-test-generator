package io.graphrag.testlib.adapter.real;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthClientTest {

    @Test
    void loginExtractsAndCachesToken() throws Exception {
        int[] hits = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            hits[0]++;
            byte[] b = "{\"token\":\"jwt-xyz\"}".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token");
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(hits[0]).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
