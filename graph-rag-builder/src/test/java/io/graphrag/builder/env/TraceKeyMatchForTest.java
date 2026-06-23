package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-007: 변형 stub 매칭 조건 생성. otel는 traceparent 전체 값에 substring(containing). */
class TraceKeyMatchForTest {

    @Test
    void otelMatchesContainingWithinFullTraceparent() {
        StringValuePattern p = new OtelTraceKey().matchFor("abc123");
        assertThat(p.match("00-abc123-span01-01").isExactMatch()).isTrue();
        assertThat(p.match("00-other-span01-01").isExactMatch()).isFalse();
        assertThat(new OtelTraceKey().headerName()).isEqualTo("traceparent");
    }

    @Test
    void sleuthMatchesEqualTo() {
        assertThat(new SleuthTraceKey().matchFor("tid9").match("tid9").isExactMatch()).isTrue();
        assertThat(new SleuthTraceKey().matchFor("tid9").match("tid9x").isExactMatch()).isFalse();
        assertThat(new SleuthTraceKey().headerName()).isEqualTo("X-B3-TraceId");
    }

    @Test
    void noneMatchForNull() {
        assertThat(new NoTraceKey().matchFor("x")).isNull();
    }
}
