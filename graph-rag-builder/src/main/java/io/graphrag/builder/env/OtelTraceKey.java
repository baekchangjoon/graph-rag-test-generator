package io.graphrag.builder.env;

import java.util.Map;
import java.util.Optional;

/** OpenTelemetry W3C traceparent: "version-traceid-spanid-flags" 에서 traceid 추출. */
public final class OtelTraceKey implements TraceKey {

    @Override
    public Optional<String> readTraceId(Map<String, String> outboundHeaders) {
        return TraceKey.headerIgnoreCase(outboundHeaders, "traceparent")
                .map(v -> v.split("-"))
                .filter(parts -> parts.length >= 2 && !parts[1].isBlank())
                .map(parts -> parts[1]);
    }
}
