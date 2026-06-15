package io.graphrag.builder.index;

import io.graphrag.model.KafkaConsumer;

import java.util.List;
import java.util.Map;

public record KafkaIndexResult(
        List<KafkaConsumer> consumers,
        Map<String, BodyShape> payloadShapes) {
}
