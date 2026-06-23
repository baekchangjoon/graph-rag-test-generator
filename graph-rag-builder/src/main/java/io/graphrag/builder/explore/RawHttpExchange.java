package io.graphrag.builder.explore;

import java.util.Map;

/** 분석 중 관측된 외부 HTTP 교환 1건 (CapturedHttpCall의 원재료). */
public record RawHttpExchange(
        String method,
        String urlPath,
        Map<String, String> query,
        String requestBody,
        int status,
        String responseBody,
        boolean baggagePresent,
        String outboundTraceId) {
}
