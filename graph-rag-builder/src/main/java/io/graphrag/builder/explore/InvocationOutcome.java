package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BranchRef;

import java.util.Set;

/** 입력 1회 실행 결과. logStart/logEnd는 SUT 로그의 캡처 구간 (sink 파싱용). */
public record InvocationOutcome(
        int status,
        JsonNode response,
        Set<BranchRef> coveredBranches,
        long logStart,
        long logEnd,
        java.util.List<RawHttpExchange> httpExchanges) {

    public InvocationOutcome {
        httpExchanges = httpExchanges == null ? java.util.List.of() : httpExchanges;
    }

    public InvocationOutcome(int status, JsonNode response, Set<BranchRef> coveredBranches,
                             long logStart, long logEnd) {
        this(status, response, coveredBranches, logStart, logEnd, java.util.List.of());
    }
}
