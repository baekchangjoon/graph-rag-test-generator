package io.graphrag.model;

import java.util.List;
import java.util.Map;

/** 분석 실행 중 SUT가 발행한 외부 HTTP 호출 1건 (docs/03, Phase 2). */
public record CapturedHttpCall(
        String id,
        String pathId,
        String method,
        String urlPath,
        Map<String, String> query,
        String requestBody,
        int responseStatus,
        String responseBody,
        List<String> consumedFields,
        boolean baggagePropagated) {

    public CapturedHttpCall {
        query = query == null ? Map.of() : query;
        consumedFields = consumedFields == null ? List.of() : consumedFields;
    }
}
