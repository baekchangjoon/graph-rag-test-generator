package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BranchRef;

import java.util.List;

/** 분기 집합 기준으로 dedupe된 대표 path. branches는 정렬되어 결정적이다. */
public record PathCandidate(
        String pathId,
        JsonNode body,
        int status,
        JsonNode response,
        List<BranchRef> branches,
        String discoveredBy,
        long logStart,
        long logEnd) {
}
