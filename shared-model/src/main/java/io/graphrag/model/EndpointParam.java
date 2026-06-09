package io.graphrag.model;

public record EndpointParam(
        String name,
        String javaType,
        ParamKind kind) {
}
