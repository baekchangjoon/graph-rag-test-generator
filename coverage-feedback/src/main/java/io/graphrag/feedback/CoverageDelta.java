package io.graphrag.feedback;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Output of {@link CoverageDeltaCalculator#compute}. Serialized as
 * {@code coverage-delta.json}; {@link TerminationDecision} reads it back.
 *
 * <p>The shape mirrors the workorder § 5.4 contract verbatim so a human can
 * round-trip the JSON without referring to source.
 */
public record CoverageDelta(
        @JsonProperty("newly_covered") List<String> newlyCovered,
        @JsonProperty("still_missing") List<MissingBranch> stillMissing,
        @JsonProperty("branch_coverage_total") double branchCoverage,
        @JsonProperty("line_coverage_total") double lineCoverage) {

    public CoverageDelta {
        newlyCovered = List.copyOf(newlyCovered);
        stillMissing = List.copyOf(stillMissing);
    }
}
