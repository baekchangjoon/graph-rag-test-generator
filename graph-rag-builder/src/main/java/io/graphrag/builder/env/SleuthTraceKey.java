package io.graphrag.builder.env;

import java.util.Map;
import java.util.Optional;

/** Spring Cloud Sleuth B3: X-B3-TraceId 헤더 값을 trace-id로 사용. */
public final class SleuthTraceKey implements TraceKey {

    @Override
    public Optional<String> readTraceId(Map<String, String> outboundHeaders) {
        return TraceKey.headerIgnoreCase(outboundHeaders, "X-B3-TraceId");
    }
}
