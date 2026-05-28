package io.graphrag.feedback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageDeltaCalculatorTest {

    @Test
    void newly_covered_is_previous_missing_minus_current_missing() {
        // Previous iteration's still-missing.
        List<MissingBranch> previous = List.of(
                mb("A.java:10"),
                mb("A.java:20"),
                mb("B.java:5"));
        // Current report: 10 is covered, 20 and 5 are still missing.
        CoverageReport current = report(0.7, 0.8, List.of(mb("A.java:20"), mb("B.java:5")));

        CoverageDelta delta = CoverageDeltaCalculator.compute(current, previous);

        assertThat(delta.newlyCovered()).containsExactly("A.java:10");
        assertThat(delta.stillMissing()).hasSize(2);
        assertThat(delta.branchCoverage()).isEqualTo(0.7);
    }

    @Test
    void newly_covered_does_not_credit_brand_new_branches_added_this_iteration() {
        // Stage 1 found a new file → JaCoCo now sees C.java:1 missing for the first time.
        // We should NOT count that as "newly covered" against the previous still-missing.
        List<MissingBranch> previous = List.of(mb("A.java:10"));
        CoverageReport current = report(0.5, 0.5,
                List.of(mb("A.java:10"), mb("C.java:1")));   // A still missing, C newly missing

        CoverageDelta delta = CoverageDeltaCalculator.compute(current, previous);

        assertThat(delta.newlyCovered()).isEmpty();
        assertThat(delta.stillMissing()).hasSize(2);
    }

    @Test
    void empty_previous_means_everything_is_neither_covered_nor_not_covered() {
        CoverageReport current = report(0.6, 0.7, List.of(mb("X.java:1")));

        CoverageDelta delta = CoverageDeltaCalculator.compute(current, List.of());

        assertThat(delta.newlyCovered()).isEmpty();
        assertThat(delta.stillMissing()).hasSize(1);
        assertThat(delta.branchCoverage()).isEqualTo(0.6);
    }

    private static MissingBranch mb(String id) {
        return new MissingBranch(id, "X.java", 1, 1, 0);
    }

    private static CoverageReport report(double branch, double line, List<MissingBranch> missing) {
        return new CoverageReport(branch, line, missing);
    }
}
