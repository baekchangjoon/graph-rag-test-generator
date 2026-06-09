package io.graphrag.testlib.adapter.dashboard;

import com.sun.net.httpserver.HttpServer;
import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import io.graphrag.testlib.spi.DashboardReporter;
import io.graphrag.testlib.spi.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HttpDashboardReporterTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TestEvent event() {
        return new TestEvent(EventType.SCOPE_CREATED, "t-abc", "run-1",
                Instant.parse("2026-06-10T00:00:00Z"), Json.mapper().nullNode());
    }

    @Test
    void report_postsEventToDashboard() {
        String url = "http://localhost:" + server.getAddress().getPort();
        DashboardReporter reporter = new HttpDashboardReporter(url);

        reporter.report(event());

        await().untilAsserted(() -> assertThat(received).hasSize(1));
        assertThat(received.get(0)).contains("SCOPE_CREATED").contains("t-abc");
    }

    @Test
    void report_neverThrowsWhenDashboardUnreachable() {
        DashboardReporter reporter = new HttpDashboardReporter("http://localhost:1");
        reporter.report(event());   // 예외 없이 무시되어야 함 (fire-and-forget)
    }

    @Test
    void adapter_selectsNoopWhenUrlMissing() {
        DashboardReporter reporter = DashboardReporters.fromEnv(Env.of(Map.of()));
        assertThat(reporter.getClass().getSimpleName()).isEqualTo("NoopDashboardReporter");
    }

    @Test
    void adapter_selectsHttpWhenUrlSet() {
        DashboardReporter reporter = DashboardReporters.fromEnv(
                Env.of(Map.of("DASHBOARD_URL", "http://localhost:9")));
        assertThat(reporter.getClass().getSimpleName()).isEqualTo("HttpDashboardReporter");
    }
}
