package io.graphrag.scout.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * A single HTTP request the scout issues against the running SUT.
 *
 * <p>Each step gets a {@code path-id} the launcher sends as both
 * {@code X-Graphrag-Path-Id} header and as an OTEL {@code baggage} entry
 * {@code graphrag.path-id=...}. SUT-side capture associates DB calls with that id.
 */
public record CaptureStep(
    @JsonProperty("path-id")         String pathId,
    @JsonProperty("method")          String method,
    @JsonProperty("path")            String path,
    @JsonProperty("body")            String body,
    @JsonProperty("content-type")    String contentType,
    @JsonProperty("headers")         Map<String, String> headers,
    @JsonProperty("expected-status") Integer expectedStatus
) {
    public CaptureStep {
        Objects.requireNonNull(pathId, "scout.steps[].path-id required");
        Objects.requireNonNull(path, "scout.steps[].path required");
        method = (method == null || method.isBlank()) ? "GET" : method.toUpperCase();
        headers = Map.copyOf(Objects.requireNonNullElse(headers, Map.of()));
        if (expectedStatus == null) expectedStatus = 0;   // 0 = don't check
    }
}
