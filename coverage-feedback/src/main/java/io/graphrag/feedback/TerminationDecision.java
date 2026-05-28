package io.graphrag.feedback;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Final yes/no on whether the orchestrator should iterate once more or stop.
 *
 * <p>Three reasons to stop, mirroring the workorder § 4 T5 contract:
 * <ol>
 *   <li>{@code target_reached} — current branch coverage ≥ target.</li>
 *   <li>{@code two_iterations_no_progress} — the last two iterations both had
 *       an empty {@code newly_covered}. Catches the case where Stage 1 keeps
 *       re-finding the same paths but can't reach more branches (R6 — no
 *       convergence guarantee). Requires at least 3 iterations of history,
 *       because iteration 1 has no previous baseline to compare against and
 *       therefore reports a structurally-empty {@code newly_covered} that
 *       must not count toward the no-progress rule.</li>
 *   <li>{@code zero_paths_discovered} — Stage 1 returned no paths at all
 *       (typically a configuration error). Surface and stop rather than loop.</li>
 * </ol>
 *
 * <p>When {@code shouldTerminate=false}, the orchestrator should feed
 * {@link FocusHintGenerator}'s output back into Stage 1 for the next iteration.
 */
public record TerminationDecision(
        @JsonProperty("should_terminate")    boolean shouldTerminate,
        @JsonProperty("termination_reason")  String reason,
        @JsonProperty("target_reached")      boolean targetReached) {

    public static TerminationDecision decide(double currentBranchCoverage,
                                             double targetBranchCoverage,
                                             List<List<String>> historyOfNewlyCovered) {
        if (currentBranchCoverage >= targetBranchCoverage) {
            return new TerminationDecision(true, "target_reached", true);
        }
        int n = historyOfNewlyCovered.size();
        // Need at least 3 iterations: iter 1 has no previous-iteration baseline so
        // CoverageDeltaCalculator returns an empty newly_covered for it regardless
        // of progress. Counting that vacuous emptiness toward the rule would let it
        // fire at iter 2 even when iter 2 itself genuinely covered new branches.
        if (n >= 3
                && historyOfNewlyCovered.get(n - 1).isEmpty()
                && historyOfNewlyCovered.get(n - 2).isEmpty()) {
            return new TerminationDecision(true, "two_iterations_no_progress", false);
        }
        return new TerminationDecision(false, null, false);
    }
}
