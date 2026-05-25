package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#HTTP_STUB_REMOVED} payload.
 */
public record HttpStubRemovedPayload(String stubId) {
    public HttpStubRemovedPayload {
        Objects.requireNonNull(stubId, "stubId");
    }
}
