package io.graphrag.testlib.adapter.real;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.model.RequestHeaders;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthClientTest {

    @Test
    void loginThrowsOnNon2xxResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token",
                    RequestHeaders.empty());
            assertThatThrownBy(() -> client.login("admin", "password"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            server.stop(0);
        }
    }

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
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token",
                    RequestHeaders.empty());
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(client.login("admin", "password")).isEqualTo("jwt-xyz");
            assertThat(hits[0]).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesCustomHeadersOnLoginWhenOnLoginTrue() throws Exception {
        String[] captured = {null};
        HttpServer server = loginServer(captured);
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            RequestHeaders headers = RequestHeaders.parse(
                    List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), true);
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token", headers);
            client.login("admin", "password");
            assertThat(captured[0]).isNotNull().matches("\\d{14}0900");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotApplyCustomHeadersOnLoginWhenOnLoginFalse() throws Exception {
        String[] captured = {null};
        HttpServer server = loginServer(captured);
        server.start();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            RequestHeaders headers = RequestHeaders.parse(
                    List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), false);
            JwtAuthClient client = new JwtAuthClient(base, "/api/auth/login", "token", headers);
            client.login("admin", "password");
            assertThat(captured[0]).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer loginServer(String[] captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/login", ex -> {
            captured[0] = ex.getRequestHeaders().getFirst("X-AuthorizationTime");
            byte[] b = "{\"token\":\"jwt-xyz\"}".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        return server;
    }
}
