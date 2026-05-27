package io.graphrag.feedback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminationDecisionTest {

    @Test
    void target_reached_terminates_immediately() {
        TerminationDecision d = TerminationDecision.decide(0.85, 0.85, List.of(List.of("x")));
        assertThat(d.shouldTerminate()).isTrue();
        assertThat(d.reason()).isEqualTo("target_reached");
        assertThat(d.targetReached()).isTrue();
    }

    @Test
    void target_just_exceeded_also_terminates() {
        TerminationDecision d = TerminationDecision.decide(0.86, 0.85, List.of(List.of("x")));
        assertThat(d.shouldTerminate()).isTrue();
        assertThat(d.reason()).isEqualTo("target_reached");
    }

    @Test
    void two_consecutive_empty_newly_covered_terminates() {
        TerminationDecision d = TerminationDecision.decide(0.5, 0.85,
                List.of(List.of("x"), List.of(), List.of()));
        assertThat(d.shouldTerminate()).isTrue();
        assertThat(d.reason()).isEqualTo("two_iterations_no_progress");
        assertThat(d.targetReached()).isFalse();
    }

    @Test
    void single_empty_iteration_does_not_terminate() {
        TerminationDecision d = TerminationDecision.decide(0.5, 0.85,
                List.of(List.of("x"), List.of()));
        assertThat(d.shouldTerminate()).isFalse();
        assertThat(d.reason()).isNull();
    }

    @Test
    void non_consecutive_empties_do_not_terminate() {
        TerminationDecision d = TerminationDecision.decide(0.5, 0.85,
                List.of(List.of(), List.of("y"), List.of()));
        assertThat(d.shouldTerminate()).isFalse();
    }

    @Test
    void target_reached_wins_over_no_progress() {
        // Even if the last two iterations had empty newly_covered, target_reached takes priority.
        TerminationDecision d = TerminationDecision.decide(0.9, 0.85,
                List.of(List.of(), List.of()));
        assertThat(d.reason()).isEqualTo("target_reached");
    }
}
