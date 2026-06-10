package io.graphrag.model;

import java.util.List;
import java.util.Map;

/** 탐색 종료 후 still_missing 리포트 (docs/22). */
public record ExplorationReport(List<EndpointExploration> endpoints) {

    public record EndpointExploration(
            String endpointId,
            int totalBranches,
            int coveredBranches,
            List<BranchRef> missedBranches,
            Map<String, Integer> pathsByEngine) {
    }
}
