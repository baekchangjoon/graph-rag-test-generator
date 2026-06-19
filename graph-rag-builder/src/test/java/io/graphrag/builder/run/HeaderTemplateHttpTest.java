package io.graphrag.builder.run;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;

class HeaderTemplateHttpTest {
    static HttpServer server; static int port;

    @BeforeAll static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/check", ex -> {
            String t = ex.getRequestHeaders().getFirst("X-AuthorizationTime");
            int code = fresh(t) ? 200 : 401;
            ex.sendResponseHeaders(code, -1); ex.close();
        });
        server.start();
    }
    @AfterAll static void down() { server.stop(0); }

    static boolean fresh(String t) {
        if (t == null || !t.matches("\\d{14}0900")) return false;
        var f = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Seoul"));
        var sent = LocalDateTime.parse(t.substring(0,14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(ZoneId.of("Asia/Seoul")).toInstant();
        return Math.abs(java.time.Duration.between(sent, Instant.now()).toMinutes()) < 5;
    }

    @Test void freshTimestampHeaderAccepted() throws Exception {
        io.graphrag.model.RequestHeaders h = io.graphrag.model.RequestHeaders.parse(
                java.util.List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), false);
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/check"));
        h.resolved(Instant.now()).forEach(b::header);
        var resp = HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, resp.statusCode());
    }
    @Test void missingHeaderRejected() throws Exception {
        var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/check")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(401, resp.statusCode());
    }
}
