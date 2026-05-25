package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#HTTP_STUB_REGISTERED} payload.
 *
 * <p>{@link #scopeBaggageValue()}는 OTEL baggage propagation 으로 격리되는 testId 값.
 */
public record HttpStubRegisteredPayload(
        String stubId,
        String urlPattern,
        String scopeBaggageValue) {

    public HttpStubRegisteredPayload {
        Objects.requireNonNull(stubId, "stubId");
        Objects.requireNonNull(urlPattern, "urlPattern");
    }
}
