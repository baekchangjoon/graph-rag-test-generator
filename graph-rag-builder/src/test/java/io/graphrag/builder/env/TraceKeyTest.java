package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-007: 모드-중립 outbound trace-id 추출(헤더 대소문자 무시). */
class TraceKeyTest {

    @Test
    void otelParsesTraceparent() {
        assertThat(new OtelTraceKey().readTraceId(Map.of("traceparent", "00-abc123-def-01")))
                .contains("abc123");
    }

    @Test
    void otelHeaderLookupIsCaseInsensitive() {
        assertThat(new OtelTraceKey().readTraceId(Map.of("TraceParent", "00-abc123-def-01")))
                .contains("abc123");
    }

    @Test
    void sleuthReadsB3() {
        assertThat(new SleuthTraceKey().readTraceId(Map.of("X-B3-TraceId", "tid9"))).contains("tid9");
    }

    @Test
    void sleuthHeaderLookupIsCaseInsensitive() {
        assertThat(new SleuthTraceKey().readTraceId(Map.of("x-b3-traceid", "tid9"))).contains("tid9");
    }

    @Test
    void noneIsEmpty() {
        assertThat(new NoTraceKey().readTraceId(Map.of("traceparent", "x"))).isEmpty();
    }

    @Test
    void forModeSelectsImplementation() {
        assertThat(TraceKey.forMode("otel")).isInstanceOf(OtelTraceKey.class);
        assertThat(TraceKey.forMode("sleuth")).isInstanceOf(SleuthTraceKey.class);
        assertThat(TraceKey.forMode("none")).isInstanceOf(NoTraceKey.class);
        assertThat(TraceKey.forMode("unknown")).isInstanceOf(NoTraceKey.class);
    }
}
