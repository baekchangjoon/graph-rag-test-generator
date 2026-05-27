package io.graphrag.scout.orchestrate;

import io.graphrag.scout.config.CaptureStep;

import java.util.Map;

/**
 * Observed outcome of one {@link CaptureStep} that {@link HttpScout} issued against the SUT.
 *
 * <p>Captured live during the scout phase and persisted by {@link ScoutMetadataWriter} as
 * the {@code endpoints.json} / {@code paths.json} entries that {@code test-generator
 * --archive} reads when synthesizing a replay test.
 *
 * @param requestHeaders snapshot of headers actually sent (includes scout-added
 *                       {@code X-Graphrag-Path-Id} and {@code baggage})
 * @param responseStatus observed HTTP status; non-2xx is recorded as-is (the synthesized
 *                       test will assert the same status)
 * @param responseBody   raw response body string (may be JSON, plain text, or empty)
 */
public record ScoutResult(
        CaptureStep step,
        Map<String, String> requestHeaders,
        String requestBody,
        int responseStatus,
        Map<String, String> responseHeaders,
        String responseBody) {

    public ScoutResult {
        requestHeaders = Map.copyOf(requestHeaders);
        responseHeaders = Map.copyOf(responseHeaders);
    }
}
