package io.graphrag.builder.cli;

import io.graphrag.builder.store.GraphPartitioner;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 변경 파일 → 더티 파티션 → 재탐색 대상 산출 (roadmap 6.2).
 * 클린 파티션의 endpoint는 이전 그래프의 탐색 사실(paths/sql/httpCalls/wsExchanges)을
 * 이월하고, 더티 파티션과 이전 탐색이 없는 endpoint만 재탐색한다.
 * 삭제된 endpoint의 사실은 이월 대상에서 제외된다 (현재 인덱스 기준).
 */
public class IncrementalBuildPlanner {

    public IncrementalPlan plan(GraphAsset previous, List<String> changedFiles,
                                List<Endpoint> endpoints, List<WsEndpoint> wsEndpoints) {
        Set<String> partitions = new LinkedHashSet<>();
        endpoints.forEach(e -> partitions.add(GraphPartitioner.partitionOf(e.handlerClass())));
        wsEndpoints.forEach(w -> partitions.add(GraphPartitioner.partitionOf(w.handlerClass())));
        Set<String> dirty = GraphPartitioner.dirtyPartitions(changedFiles, partitions);

        Set<String> explore = new LinkedHashSet<>();
        Set<String> carriedEndpointIds = new LinkedHashSet<>();
        for (Endpoint endpoint : endpoints) {
            boolean hasPrevious = previous.paths().stream()
                    .anyMatch(p -> p.endpointId().equals(endpoint.id()));
            if (dirty.contains(GraphPartitioner.partitionOf(endpoint.handlerClass()))
                    || !hasPrevious) {
                explore.add(endpoint.id());
            } else {
                carriedEndpointIds.add(endpoint.id());
            }
        }
        Set<String> carriedWsEndpointIds = new LinkedHashSet<>();
        for (WsEndpoint wsEndpoint : wsEndpoints) {
            boolean hasPrevious = previous.wsExchanges().stream()
                    .anyMatch(x -> x.wsEndpointId().equals(wsEndpoint.id()));
            if (dirty.contains(GraphPartitioner.partitionOf(wsEndpoint.handlerClass()))
                    || !hasPrevious) {
                explore.add(wsEndpoint.id());
            } else {
                carriedWsEndpointIds.add(wsEndpoint.id());
            }
        }

        List<ExploredPath> carriedPaths = previous.paths().stream()
                .filter(p -> carriedEndpointIds.contains(p.endpointId())).toList();
        List<WsExchange> carriedWsExchanges = previous.wsExchanges().stream()
                .filter(x -> carriedWsEndpointIds.contains(x.wsEndpointId())).toList();
        Set<String> carriedPathIds = new LinkedHashSet<>();
        carriedPaths.forEach(p -> carriedPathIds.add(p.id()));
        carriedWsExchanges.forEach(x -> carriedPathIds.add(x.id()));

        return new IncrementalPlan(explore, carriedPaths,
                previous.sql().stream()
                        .filter(s -> carriedPathIds.contains(s.pathId())).toList(),
                previous.httpCalls().stream()
                        .filter(c -> carriedPathIds.contains(c.pathId())).toList(),
                carriedWsExchanges);
    }
}
