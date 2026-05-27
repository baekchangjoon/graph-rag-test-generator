package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.CaptureStep;
import io.graphrag.scout.config.ScoutConfig.ScoutSection;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Executes the configured HTTP {@link CaptureStep}s against the live SUT.
 *
 * <p>Each request carries both {@code X-Graphrag-Path-Id: <pathId>} and a W3C
 * {@code baggage: graphrag.path-id=<pathId>} header so the SUT-side capture (bridge or
 * filter) can associate every JDBC call with the right path id regardless of which
 * propagation mechanism it listens to.
 */
public final class HttpScout {

    public static final String HEADER_PATH_ID = "X-Graphrag-Path-Id";

    private final HttpClient http;
    private final ScoutSection cfg;

    public HttpScout(ScoutSection cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void run() throws IOException, InterruptedException {
        for (CaptureStep step : cfg.steps()) {
            issue(step);
        }
    }

    void issue(CaptureStep step) throws IOException, InterruptedException {
        URI uri = URI.create(cfg.baseUrl() + step.path());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header(HEADER_PATH_ID, step.pathId())
                .header("baggage", "graphrag.path-id=" + step.pathId());
        if (step.contentType() != null && !step.contentType().isBlank()) {
            b.header("Content-Type", step.contentType());
        } else if (step.body() != null) {
            b.header("Content-Type", "application/json");
        }
        for (var e : step.headers().entrySet()) b.header(e.getKey(), e.getValue());

        HttpRequest.BodyPublisher body = step.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(step.body());
        b.method(step.method(), body);

        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("[scout] " + step.method() + " " + step.path()
                + " -> " + resp.statusCode() + " (pathId=" + step.pathId() + ")");
        if (step.expectedStatus() > 0 && resp.statusCode() != step.expectedStatus()) {
            System.err.println("[scout] WARN: expected " + step.expectedStatus()
                    + " but got " + resp.statusCode() + " for " + step.pathId());
        }
    }

    public List<CaptureStep> steps() { return cfg.steps(); }
}
