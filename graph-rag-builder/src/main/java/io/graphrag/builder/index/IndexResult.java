package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;

import java.util.List;
import java.util.Map;

public record IndexResult(
        List<Endpoint> endpoints,
        Map<String, BodyShape> bodyShapes) {
}
