package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.ExploredPath;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link BranchAnalyzer#analyze}. Both lists are unmodifiable and
 * deterministic — same input → equal result records.
 */
public record BranchAnalysisResult(
        List<ExploredPath> paths,
        List<ManualReviewItem> manualReviewQueue) {

    public BranchAnalysisResult {
        paths              = List.copyOf(Objects.requireNonNull(paths,              "paths"));
        manualReviewQueue  = List.copyOf(Objects.requireNonNull(manualReviewQueue,  "manualReviewQueue"));
    }
}
