package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#SCOPE_CLEANED} payload.
 */
public record ScopeCleanedPayload(ResourcesReleased resourcesReleased) {
    public ScopeCleanedPayload {
        Objects.requireNonNull(resourcesReleased, "resourcesReleased");
    }
}
