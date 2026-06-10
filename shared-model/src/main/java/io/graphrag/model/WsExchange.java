package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 분석 중 캡처된 STOMP 메시지 교환 1건 (roadmap 3.2). */
public record WsExchange(
        String id,
        String wsEndpointId,
        JsonNode payload,
        String responseDestination,
        JsonNode response,
        List<String> capturedSqlIds) {

    public WsExchange {
        capturedSqlIds = capturedSqlIds == null ? List.of() : capturedSqlIds;
    }
}
