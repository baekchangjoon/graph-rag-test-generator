package io.graphrag.builder.staticanalysis.branch;

import java.util.Objects;

/**
 * Static analyzer surfaced something it cannot generate a deterministic input for
 * (missing method analysis, complex parameter type, etc). Surfaced via
 * {@link BranchAnalysisResult#manualReviewQueue()} for downstream tooling to log
 * or escalate.
 */
public record ManualReviewItem(String kind, String reason, String location) {

    public ManualReviewItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(location, "location");
    }
}
