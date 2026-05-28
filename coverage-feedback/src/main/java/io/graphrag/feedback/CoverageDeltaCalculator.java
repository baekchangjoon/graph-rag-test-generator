package io.graphrag.feedback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the iteration-to-iteration delta:
 * <ul>
 *   <li>{@code newlyCovered} = branches present in the previous iteration's
 *       still-missing set, no longer in the current iteration's missing set.</li>
 *   <li>{@code stillMissing} = current iteration's missing set (verbatim).</li>
 * </ul>
 *
 * <p>The convention "compare to previous iteration's still_missing, not previous
 * iteration's totals" matters: if the report's overall branch count grows (e.g.
 * Stage 1 found new endpoints between iterations), we don't want to credit those
 * new branches as "newly covered" — we want to know whether *the branches we
 * specifically failed to cover before* got covered.
 */
public final class CoverageDeltaCalculator {

    private CoverageDeltaCalculator() {}

    public static CoverageDelta compute(CoverageReport current,
                                        List<MissingBranch> previousStillMissing) {
        Set<String> currentMissingIds = current.missing().stream()
                .map(MissingBranch::branchId)
                .collect(Collectors.toSet());

        List<String> newlyCovered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MissingBranch prev : previousStillMissing) {
            if (seen.add(prev.branchId()) && !currentMissingIds.contains(prev.branchId())) {
                newlyCovered.add(prev.branchId());
            }
        }
        return new CoverageDelta(newlyCovered, current.missing(),
                current.branchCoverage(), current.lineCoverage());
    }
}
