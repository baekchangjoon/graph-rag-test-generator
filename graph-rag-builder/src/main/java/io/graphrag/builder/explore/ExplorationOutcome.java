package io.graphrag.builder.explore;

import io.graphrag.model.BranchRef;

import java.util.List;
import java.util.Set;

public record ExplorationOutcome(
        List<PathCandidate> paths,
        Set<BranchRef> coveredBranches,
        java.util.Map<String, Integer> pathsByEngine) {
}
