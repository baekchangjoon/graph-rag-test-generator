package io.graphrag.builder.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceParentTest {

    @Test
    void format_isW3C() {
        TraceParent tp = new TraceParent("run-1");
        TraceParent.Ids ids = tp.next();
        assertThat(ids.header()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(ids.traceId()).hasSize(32);
        assertThat(ids.spanId()).hasSize(16);
    }

    @Test
    void deterministic_sameRunSameSequence() {
        assertThat(new TraceParent("run-1").next().header())
                .isEqualTo(new TraceParent("run-1").next().header());
    }

    @Test
    void unique_acrossRequestsAndRuns() {
        TraceParent tp = new TraceParent("run-1");
        assertThat(tp.next().traceId()).isNotEqualTo(tp.next().traceId());
        assertThat(new TraceParent("run-1").next().traceId())
                .isNotEqualTo(new TraceParent("run-2").next().traceId());
    }
}
