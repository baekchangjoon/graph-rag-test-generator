package io.graphrag.builder.capture.otlp;

import java.util.List;
import java.util.Map;

/**
 * OTLP/protobuf에서 디코드된 span 1건 (필요한 필드만).
 * attributes는 평탄화된 문자열 맵(예: "db.query.text", "db.query.parameter.0").
 * links는 이 span이 참조하는 traceId 목록.
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        long startUnixNano,
        Map<String, String> attributes,
        List<String> linkedTraceIds) {
}
