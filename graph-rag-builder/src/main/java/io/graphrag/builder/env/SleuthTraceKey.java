package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;

import java.util.Map;
import java.util.Optional;

/** Spring Cloud Sleuth B3: X-B3-TraceId 헤더 값을 trace-id로 사용. */
public final class SleuthTraceKey implements TraceKey {

    @Override
    public Optional<String> readTraceId(Map<String, String> outboundHeaders) {
        return TraceKey.headerIgnoreCase(outboundHeaders, "X-B3-TraceId");
    }

    @Override
    public StringValuePattern matchFor(String traceId) {
        // X-B3-TraceId 값이 곧 trace-id → 정확히 일치.
        return WireMock.equalTo(traceId);
    }

    @Override
    public String headerName() {
        return "X-B3-TraceId";
    }
}
