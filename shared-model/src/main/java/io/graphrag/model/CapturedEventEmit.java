package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

public record CapturedEventEmit(
        String id,
        String pathId,
        String topic,
        String key,
        JsonNode payload
) {}
