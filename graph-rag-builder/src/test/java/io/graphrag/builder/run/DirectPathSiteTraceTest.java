package io.graphrag.builder.run;

import io.graphrag.builder.explore.InvocationOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectPathSiteTraceTest {

    @Test
    void coverageTraceIdsOfNonNull() {
        InvocationOutcome o = new InvocationOutcome(401, null, java.util.Set.of(), 0, 0,
                java.util.List.of(), null, java.util.List.of(), java.util.List.of(), null,
                java.util.Map.of(), java.util.List.of(), "tid-7");
        assertThat(EndpointExplorationRunner.coverageTraceIdsOf(o)).containsExactly("tid-7");
    }

    @Test
    void coverageTraceIdsOfNull() {
        InvocationOutcome o = new InvocationOutcome(401, null, java.util.Set.of(), 0, 0);
        assertThat(EndpointExplorationRunner.coverageTraceIdsOf(o)).isEmpty();
    }
}
