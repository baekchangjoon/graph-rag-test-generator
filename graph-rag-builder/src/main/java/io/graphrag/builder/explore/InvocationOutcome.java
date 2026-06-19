package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedEventEmit;

import java.util.Set;

/**
 * 입력 1회 실행 결과. logStart/logEnd는 SUT 로그의 캡처 구간 (sink 파싱용).
 * coverageKey: SUT 자체 클래스 probe 지문(arm-accurate). path 식별에 사용 — null이면 분기집합으로 폴백.
 * capturedSql: SqlCaptureBackend가 이 요청에 귀속해 drain한 SQL (발행 순서). 로그 재파싱 대체 채널.
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
        String kafkaTraceId) {

    public InvocationOutcome {
        httpExchanges = httpExchanges == null ? java.util.List.of() : httpExchanges;
        capturedSql = capturedSql == null ? java.util.List.of() : capturedSql;
        capturedEventEmits = capturedEventEmits == null ? java.util.List.of() : capturedEventEmits;
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey, java.util.List<io.graphrag.builder.capture.ParsedSql> capturedSql) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey, capturedSql,
                java.util.List.of(), null);
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges,
                             String coverageKey) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, coverageKey,
                java.util.List.of(), java.util.List.of(), null);
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd, java.util.List<RawHttpExchange> httpExchanges) {
        this(status, response, coveredBranches, logStart, logEnd, httpExchanges, null, java.util.List.of(), java.util.List.of(), null);
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd) {
        this(status, response, coveredBranches, logStart, logEnd, java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(), null);
    }
}
