package io.graphrag.builder.explore;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InvocationOutcomeTraceTest {
    @Test
    void carriesCoverageTraceId() {
        InvocationOutcome o = new InvocationOutcome(200, null, java.util.Set.of(), 0L, 0L,
                List.of(), "covKey", List.of(), List.of(), null, java.util.Map.of(), List.of(), "abc123");
        assertThat(o.coverageTraceId()).isEqualTo("abc123");
    }
    @Test
    void legacyConstructorDefaultsNullTraceId() {
        InvocationOutcome o = new InvocationOutcome(200, null, java.util.Set.of(), 0L, 0L);
        assertThat(o.coverageTraceId()).isNull();
    }
}
