package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InputCandidatesTest {

    @Test
    void mergeUnionsRealsAndRealTuples() {
        InputCandidates a = new InputCandidates(
                Map.of(), Map.of(), List.of(),
                Map.of("base", Set.of(99.5, 100.5)),
                List.of(Map.of("base", 48.5, "surcharge", 1.0)));
        InputCandidates b = new InputCandidates(
                Map.of(), Map.of(), List.of(),
                Map.of("base", Set.of(100.5, 101.5)),
                List.of(Map.of("base", 0.0, "surcharge", 33.3)));

        InputCandidates merged = a.merge(b);

        assertThat(merged.reals().get("base")).containsExactly(99.5, 100.5, 101.5);
        assertThat(merged.realTuples()).hasSize(2)
                .anySatisfy(t -> assertThat(t).containsEntry("base", 48.5).containsEntry("surcharge", 1.0))
                .anySatisfy(t -> assertThat(t).containsEntry("base", 0.0).containsEntry("surcharge", 33.3));
    }

    @Test
    void mergeDeduplicatesIdenticalRealTuples() {
        Map<String, Double> tuple = Map.of("base", 48.5, "surcharge", 1.0);
        InputCandidates a = new InputCandidates(Map.of(), Map.of(), List.of(),
                Map.of(), List.of(tuple));
        InputCandidates b = new InputCandidates(Map.of(), Map.of(), List.of(),
                Map.of(), List.of(tuple));

        assertThat(a.merge(b).realTuples()).hasSize(1);
    }

    @Test
    void legacyConstructorsLeaveRealChannelsEmpty() {
        InputCandidates twoArg = new InputCandidates(
                Map.of("score", Set.of(42L)), Map.of());
        assertThat(twoArg.reals()).isEmpty();
        assertThat(twoArg.realTuples()).isEmpty();

        InputCandidates threeArg = new InputCandidates(
                Map.of(), Map.of(), List.of(Map.of("nights", 1L, "loyaltyPoints", 607L)));
        assertThat(threeArg.reals()).isEmpty();
        assertThat(threeArg.realTuples()).isEmpty();

        assertThat(InputCandidates.empty().reals()).isEmpty();
        assertThat(InputCandidates.empty().realTuples()).isEmpty();
    }
}
