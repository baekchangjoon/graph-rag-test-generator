package io.graphrag.builder.capture.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceReceiverLimitsTest {
    @Test void exposesSharedLimits() {
        assertThat(TraceReceiverLimits.MAX_TRACES).isEqualTo(50_000);
        assertThat(TraceReceiverLimits.MAX_SPANS_PER_TRACE).isEqualTo(10_000);
        assertThat(TraceReceiverLimits.HEX_32.matcher("a".repeat(32)).matches()).isTrue();
        assertThat(TraceReceiverLimits.HEX_32.matcher("A".repeat(32)).matches()).isFalse();
    }
}
