package io.graphrag.builder.env;

import com.github.tomakehurst.wiremock.matching.StringValuePattern;

import java.util.Map;
import java.util.Optional;

/**
 * SUT가 발행하는 outbound HTTP 요청 헤더에서 trace-id를 추출한다 (REQ-007).
 * 모드-중립: otel(traceparent) / sleuth(X-B3-TraceId) / none(없음). 병렬 격리의 키로 쓰인다.
 * 헤더 lookup은 대소문자를 무시한다.
 */
public interface TraceKey {

    Optional<String> readTraceId(Map<String, String> outboundHeaders);

    /**
     * 변형 stub을 이 trace-id로 격리하기 위한 WireMock 헤더 매칭 조건.
     * otel: traceparent 전체 값(00-tid-sid-flags)에 substring(containing).
     * sleuth: 헤더 값이 곧 trace-id(equalTo). none: null(헤더 조건 없음).
     */
    StringValuePattern matchFor(String traceId);

    /** matchFor가 적용될 헤더 이름. otel: "traceparent", sleuth: "X-B3-TraceId". none: 미사용. */
    String headerName();

    /** trace-mode 문자열로 구현 선택. "otel"→Otel, "sleuth"→Sleuth, 그 외→No. */
    static TraceKey forMode(String traceMode) {
        if (traceMode == null) {
            return new NoTraceKey();
        }
        return switch (traceMode.toLowerCase()) {
            case "otel" -> new OtelTraceKey();
            case "sleuth" -> new SleuthTraceKey();
            default -> new NoTraceKey();
        };
    }

    /** 헤더 Map에서 키를 대소문자 무시로 조회. */
    static Optional<String> headerIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
