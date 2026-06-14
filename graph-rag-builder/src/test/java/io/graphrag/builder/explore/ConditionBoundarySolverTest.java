package io.graphrag.builder.explore;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionBoundarySolverTest {

    @Test
    void solve_eachLiteralBecomesLMinus1_L_LPlus1_perField() {
        List<Comparison> comparisons = List.of(
                new Comparison("C", "m", "amount", ">", 100, 10),
                new Comparison("C", "m", "score", "<=", 50, 11),
                new Comparison("C", "m", "amount", "==", 7, 12));

        Map<String, Set<Long>> bounds = new ConditionBoundarySolver().solve(comparisons);

        assertThat(bounds.get("amount")).containsExactly(6L, 7L, 8L, 99L, 100L, 101L);
        assertThat(bounds.get("score")).containsExactly(49L, 50L, 51L);
    }

    @Test
    void solve_empty_returnsEmpty() {
        assertThat(new ConditionBoundarySolver().solve(List.of())).isEmpty();
    }
}
