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
        Map<String, String> responseHeaders,
        Outcome.Kind outcome,
        int semanticStatus,
        String semanticStatusText,
        List<String> coverageTraceIds) {

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
        // 역직렬화 후방호환: 구버전 JSON에 누락된 outcome/semanticStatus/semanticStatusText를 expectedStatus로 파생
        outcome = outcome == null ? deriveOutcome(expectedStatus) : outcome;
        // semanticStatus 0 = legacy JSON에 필드 없음(Jackson 기본값). HTTP 상태에 0은 없으므로 sentinel로 안전.
        semanticStatus = semanticStatus == 0 ? expectedStatus : semanticStatus;
        semanticStatusText = (semanticStatusText == null || semanticStatusText.isBlank())
                ? String.valueOf(expectedStatus) : semanticStatusText;
        coverageTraceIds = coverageTraceIds == null ? List.of() : coverageTraceIds;
    }

    private static Outcome.Kind deriveOutcome(int expectedStatus) {
        return expectedStatus / 100 == 2 ? Outcome.Kind.SUCCESS : Outcome.Kind.FAILURE;
    }

    /** 17-argument compatibility constructor: delegates to the 18-arg canonical with empty coverageTraceIds. */
    public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
            JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
            List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
            List<String> validationWarnings, List<String> requiredSeedIds, List<String> capturedEventEmitIds,
            Map<String, String> responseHeaders, Outcome.Kind outcome, int semanticStatus, String semanticStatusText) {
        this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
             branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds, capturedEventEmitIds,
             responseHeaders, outcome, semanticStatus, semanticStatusText, List.of());
    }

    /** 14-argument compatibility constructor: outcome/semanticStatus derived from expectedStatus. */
    public ExploredPath(String id, String endpointId, JsonNode sampleInput, int expectedStatus,
                        JsonNode sampleResponse, List<String> capturedSqlIds, List<String> capturedHttpCallIds,
                        List<BranchRef> branchesTaken, String discoveredBy, List<String> constraints,
                        List<String> validationWarnings, List<String> requiredSeedIds,
                        List<String> capturedEventEmitIds, Map<String, String> responseHeaders) {
        this(id, endpointId, sampleInput, expectedStatus, sampleResponse, capturedSqlIds, capturedHttpCallIds,
             branchesTaken, discoveredBy, constraints, validationWarnings, requiredSeedIds,
             capturedEventEmitIds, responseHeaders,
             deriveOutcome(expectedStatus), expectedStatus, String.valueOf(expectedStatus));
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
