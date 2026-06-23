package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;

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

    @Override
    public StringValuePattern matchFor(String traceId) {
        // traceparent 전체 값(00-tid-sid-flags)에 trace-id가 substring으로 들어 있다 → containing.
        return WireMock.containing(traceId);
    }

    @Override
    public String headerName() {
        return "traceparent";
    }
}
