package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** testlib가 fire-and-forget으로 발행하는 이벤트. */
public record TestEvent(
        EventType type,
        String testId,
        String runId,
        Instant at,
        JsonNode detail) {
}
