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
        boolean baggagePropagated,
        Provenance responseProvenance) {

    /** 응답 본문의 출처: 실제 SUT 외부 호출 캡처(CAPTURED) vs 형상에서 합성한 stub(SYNTHESIZED). */
    public enum Provenance {
        CAPTURED,
        SYNTHESIZED
    }

    public CapturedHttpCall {
        query = query == null ? Map.of() : query;
        consumedFields = consumedFields == null ? List.of() : consumedFields;
        responseProvenance = responseProvenance == null ? Provenance.CAPTURED : responseProvenance;
    }

    /** 기존 10-arg 호출부 호환: responseProvenance=CAPTURED. */
    public CapturedHttpCall(
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
        this(id, pathId, method, urlPath, query, requestBody, responseStatus,
                responseBody, consumedFields, baggagePropagated, Provenance.CAPTURED);
    }
}
