package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 탐색된 코드 경로. 분기 집합이 다르면 별개 path. */
public record ExploredPath(
        String id,
        String endpointId,
        JsonNode sampleInput,
        int expectedStatus,
        JsonNode sampleResponse,
        List<String> capturedSqlIds,
        List<String> capturedHttpCallIds,
        List<BranchRef> branchesTaken,
        String discoveredBy,
        List<String> constraints,
        List<String> validationWarnings,
        List<String> requiredSeedIds,
        List<String> capturedEventEmitIds,
        Map<String, String> responseHeaders) {

    /** 구버전 그래프(Phase 0/1)와의 후방 호환: 누락 필드를 빈 값으로 정규화. */
    public ExploredPath {
        capturedHttpCallIds = capturedHttpCallIds == null ? List.of() : capturedHttpCallIds;
        branchesTaken = branchesTaken == null ? List.of() : branchesTaken;
        discoveredBy = discoveredBy == null ? "unknown" : discoveredBy;
        constraints = constraints == null ? List.of() : constraints;
        validationWarnings = validationWarnings == null ? List.of() : validationWarnings;
        requiredSeedIds = requiredSeedIds == null ? List.of() : requiredSeedIds;
        capturedEventEmitIds = capturedEventEmitIds == null ? List.of() : capturedEventEmitIds;
        responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
    }

    /** 13-argument compatibility constructor (no responseHeaders — backward compat) */
    public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
                        JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
                        List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
                        List<String> validationWarnings, List<String> requiredSeedIds,
                        List<String> capturedEventEmitIds) {
        this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
             branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds,
             capturedEventEmitIds, Map.of());
    }

    /** 12-argument compatibility constructor */
    public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
                        JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
                        List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
                        List<String> validationWarnings, List<String> requiredSeedIds) {
        this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
             branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds, List.of(),
             Map.of());
    }
}
