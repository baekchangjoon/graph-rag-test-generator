package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 탐색된 코드 경로. 분기 집합이 다르면 별개 path. */
public record ExploredPath(
        String id,
        String endpointId,
        JsonNode sampleInput,
        int expectedStatus,
        JsonNode sampleResponse,
        List<String> capturedSqlIds,
        List<BranchRef> branchesTaken,
        String discoveredBy,
        List<String> constraints,
        List<String> validationWarnings) {

    /** 구버전 그래프(Phase 0)와의 후방 호환: 누락 필드를 빈 값으로 정규화. */
    public ExploredPath {
        branchesTaken = branchesTaken == null ? List.of() : branchesTaken;
        discoveredBy = discoveredBy == null ? "unknown" : discoveredBy;
        constraints = constraints == null ? List.of() : constraints;
        validationWarnings = validationWarnings == null ? List.of() : validationWarnings;
    }
}
