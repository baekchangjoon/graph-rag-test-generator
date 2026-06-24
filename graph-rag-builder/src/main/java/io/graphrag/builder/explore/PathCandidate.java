package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedEventEmit;

import java.util.List;
import java.util.Map;

/** 분기 집합 기준으로 dedupe된 대표 path. branches는 정렬되어 결정적이다. */
public record PathCandidate(
        String pathId,
        JsonNode body,
        int status,
        JsonNode response,
        List<BranchRef> branches,
        String discoveredBy,
        long logStart,
        long logEnd,
        List<RawHttpExchange> httpExchanges,
        List<io.graphrag.builder.capture.ParsedSql> capturedSql,
        List<CapturedEventEmit> capturedEventEmits,
        String kafkaTraceId,
        Map<String, String> responseHeaders,
        List<EgressCall> egressCalls) {

    public PathCandidate {
        capturedSql = capturedSql == null ? List.of() : capturedSql;
        capturedEventEmits = capturedEventEmits == null ? List.of() : capturedEventEmits;
        responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
        egressCalls = egressCalls == null ? List.of() : egressCalls;
    }

    /** egressCalls 생략 호환 생성자 — 기본 빈 리스트. */
    public PathCandidate(String pathId, JsonNode body, int status, JsonNode response,
                         List<BranchRef> branches, String discoveredBy, long logStart, long logEnd,
                         List<RawHttpExchange> httpExchanges, List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                         List<CapturedEventEmit> capturedEventEmits, String kafkaTraceId,
                         Map<String, String> responseHeaders) {
        this(pathId, body, status, response, branches, discoveredBy, logStart, logEnd, httpExchanges,
                capturedSql, capturedEventEmits, kafkaTraceId, responseHeaders, List.of());
    }

    public PathCandidate(String pathId, JsonNode body, int status, JsonNode response,
                         List<BranchRef> branches, String discoveredBy, long logStart, long logEnd,
                         List<RawHttpExchange> httpExchanges, List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                         List<CapturedEventEmit> capturedEventEmits, String kafkaTraceId) {
        this(pathId, body, status, response, branches, discoveredBy, logStart, logEnd, httpExchanges,
                capturedSql, capturedEventEmits, kafkaTraceId, Map.of(), List.of());
    }

    public PathCandidate(String pathId, JsonNode body, int status, JsonNode response,
                         List<BranchRef> branches, String discoveredBy, long logStart, long logEnd,
                         List<RawHttpExchange> httpExchanges, List<io.graphrag.builder.capture.ParsedSql> capturedSql) {
        this(pathId, body, status, response, branches, discoveredBy, logStart, logEnd, httpExchanges,
                capturedSql, List.of(), null, Map.of(), List.of());
    }

    public PathCandidate(String pathId, JsonNode body, int status, JsonNode response,
                         List<BranchRef> branches, String discoveredBy, long logStart, long logEnd,
                         List<RawHttpExchange> httpExchanges) {
        this(pathId, body, status, response, branches, discoveredBy, logStart, logEnd, httpExchanges,
                List.of(), List.of(), null, Map.of(), List.of());
    }
}
