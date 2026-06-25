package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedEventEmit;

import java.util.Map;
import java.util.Set;

/**
 * 입력 1회 실행 결과. logStart/logEnd는 SUT 로그의 캡처 구간 (sink 파싱용).
 * coverageKey: SUT 자체 클래스 probe 지문(arm-accurate). path 식별에 사용 — null이면 분기집합으로 폴백.
 * capturedSql: SqlCaptureBackend가 이 요청에 귀속해 drain한 SQL (발행 순서). 로그 재파싱 대체 채널.
 * responseHeaders: SUT 응답에서 캡처한 커스텀 헤더(hop-by-hop·표준 헤더는 제외).
 * egressCalls: 이 요청이 유발한 outbound HTTP egress 호출 목록(EgressCollector 수집, drain 이전).
 * coverageTraceId: per-request coverage trace ID (nullable; 미주입 probe는 null).
 */
public record InvocationOutcome(
        int status,
        JsonNode response,
        Set<BranchRef> coveredBranches,
        long logStart,
        long logEnd,
        java.util.List<RawHttpExchange> httpExchanges,
        String coverageKey,
        java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
        java.util.List<CapturedEventEmit> capturedEventEmits,
        String kafkaTraceId,
        Map<String, String> responseHeaders,
        java.util.List<EgressCall> egressCalls,
        String coverageTraceId) {

    public InvocationOutcome {
        httpExchanges = httpExchanges == null ? java.util.List.of() : httpExchanges;
        capturedSql = capturedSql == null ? java.util.List.of() : capturedSql;
        capturedEventEmits = capturedEventEmits == null ? java.util.List.of() : capturedEventEmits;
        responseHeaders = responseHeaders == null ? Map.of() : responseHeaders;
        egressCalls = egressCalls == null ? java.util.List.of() : egressCalls;
        // coverageTraceId는 nullable 그대로 유지(미주입 probe = null)
    }

    /** 기존 12-arg(egressCalls 포함) 생성자를 새 canonical로 위임 + coverageTraceId=null 추가. */
    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                             java.util.List<CapturedEventEmit> capturedEventEmits, String kafkaTraceId,
                             Map<String, String> responseHeaders, java.util.List<EgressCall> egressCalls) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                capturedEventEmits, kafkaTraceId, responseHeaders, egressCalls, null);
    }

    /** egressCalls 생략 호환 생성자 — 기본 빈 리스트. */
    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                             java.util.List<CapturedEventEmit> capturedEventEmits,
                             String kafkaTraceId, Map<String, String> responseHeaders) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                capturedEventEmits, kafkaTraceId, responseHeaders, java.util.List.of());
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql,
                             java.util.List<CapturedEventEmit> capturedEventEmits,
                             String kafkaTraceId) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                capturedEventEmits, kafkaTraceId, Map.of(), java.util.List.of());
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                java.util.List.of(), null, Map.of(), java.util.List.of());
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey,
                java.util.List.of(), java.util.List.of(), null, Map.of(), java.util.List.of());
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, null,
                java.util.List.of(), java.util.List.of(), null, Map.of(), java.util.List.of());
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd) {
        this(status, response, coveredBranches, logStart, logEnd, java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(), null, Map.of(), java.util.List.of());
    }
}
