package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Kafka consumer 1회 발행 교환: 토픽에 발행한 payload + 그 처리 중 캡처된 SQL. */
public record KafkaExchange(
        String id,
        String kafkaConsumerId,
        String topic,
        JsonNode payload,
        List<String> capturedSqlIds) {

    public KafkaExchange {
        capturedSqlIds = capturedSqlIds == null ? List.of() : capturedSqlIds;
    }
}
