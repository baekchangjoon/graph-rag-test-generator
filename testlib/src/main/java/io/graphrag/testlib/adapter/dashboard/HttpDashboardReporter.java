package io.graphrag.testlib.adapter.dashboard;

import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import io.graphrag.testlib.spi.DashboardReporter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** fire-and-forget HTTP 발행. 어떤 실패도 테스트로 전파하지 않는다. */
public final class HttpDashboardReporter implements DashboardReporter {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final URI eventsUri;
    private final HttpClient client;

    public HttpDashboardReporter(String dashboardUrl) {
        this.eventsUri = URI.create(dashboardUrl.replaceAll("/$", "") + "/events");
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public void report(TestEvent event) {
        try {
            HttpRequest request = HttpRequest.newBuilder(eventsUri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.mapper().writeValueAsString(event)))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> null);
        } catch (Exception ignored) {
            // fire-and-forget: 대시보드 장애가 테스트를 실패시키지 않는다 (docs/08)
        }
    }
}
