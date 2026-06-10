package io.graphrag.builder.index;

import io.graphrag.model.WsEndpoint;

import java.util.List;
import java.util.Map;

public record WsIndexResult(
        List<WsEndpoint> endpoints,
        Map<String, BodyShape> payloadShapes) {
}
