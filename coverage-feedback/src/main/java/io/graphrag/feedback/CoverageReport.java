package io.graphrag.feedback;

import java.util.List;

/**
 * Flat view of a JaCoCo XML report that's enough to compute
 * {@link CoverageDeltaCalculator} output and {@link TerminationDecision}.
 *
 * @param branchCoverage report-level branch coverage in [0.0, 1.0]
 * @param lineCoverage   report-level line coverage in [0.0, 1.0]
 * @param missing        every source line with at least one uncovered branch
 */
public record CoverageReport(double branchCoverage, double lineCoverage,
                             List<MissingBranch> missing) {
    public CoverageReport {
        missing = List.copyOf(missing);
    }
}
