package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 탐색된 코드 경로. Phase 0은 endpoint당 happy-path 1개. */
public record ExploredPath(
        String id,
        String endpointId,
        JsonNode sampleInput,
        int expectedStatus,
        JsonNode sampleResponse,
        List<String> capturedSqlIds) {
}
