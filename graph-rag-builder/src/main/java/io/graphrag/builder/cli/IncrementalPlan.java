package io.graphrag.builder.cli;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.WsExchange;

import java.util.List;
import java.util.Set;

/**
 * 증분 빌드 계획 (roadmap 6.2): 재탐색할 endpoint/wsEndpoint id 집합 +
 * 클린 파티션에서 이월(carry-over)할 이전 탐색 사실.
 * exploreIds가 null이면 풀빌드 (전부 탐색, 이월 없음).
 */
public record IncrementalPlan(
        Set<String> exploreIds,
        List<ExploredPath> carriedPaths,
        List<CapturedSql> carriedSql,
        List<CapturedHttpCall> carriedHttpCalls,
        List<WsExchange> carriedWsExchanges) {

    public boolean shouldExplore(String id) {
        return exploreIds == null || exploreIds.contains(id);
    }

    public static IncrementalPlan exploreAll() {
        return new IncrementalPlan(null, List.of(), List.of(), List.of(), List.of());
    }
}
