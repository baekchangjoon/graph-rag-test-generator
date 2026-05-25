package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#SCOPE_CREATED} payload.
 */
public record ScopeCreatedPayload(
        String testClass,
        String testMethod,
        String runId) {

    public ScopeCreatedPayload {
        Objects.requireNonNull(testClass, "testClass");
        Objects.requireNonNull(testMethod, "testMethod");
        Objects.requireNonNull(runId, "runId");
    }
}
