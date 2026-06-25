package io.graphrag.builder.run;

import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VariantOutcomeTraceTest {
    @Test
    void carriesTraceId() {
        var vo = new EndpointExplorationRunner.VariantOutcome(new ExecutionDataStore(), 200, "t9");
        assertThat(vo.coverageTraceId()).isEqualTo("t9");
    }
    @Test
    void twoArgCompatNullTrace() {
        var vo = new EndpointExplorationRunner.VariantOutcome(new ExecutionDataStore(), 200);
        assertThat(vo.coverageTraceId()).isNull();
    }
}
