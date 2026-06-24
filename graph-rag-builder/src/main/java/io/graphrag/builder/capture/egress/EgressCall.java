package io.graphrag.builder.capture.egress;
public record EgressCall(String method, String path, Integer statusOrNull, String traceId, long startNanos) {}
