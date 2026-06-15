package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Kafka consumer 1회 발행 교환: 토픽에 발행한 payload + 그 처리 중 캡처된 SQL.
 * variant=true이면 반대-arm 커버용 변종 발행(결측-필드/중복) — 테스트 생성에서 제외된다.
 */
public record KafkaExchange(
        String id,
        String kafkaConsumerId,
        String topic,
        JsonNode payload,
        List<String> capturedSqlIds,
        boolean variant) {

    public KafkaExchange {
        capturedSqlIds = capturedSqlIds == null ? List.of() : capturedSqlIds;
    }

    /** 후방 호환: variant 미지정 = 정상(happy) 교환. */
    public KafkaExchange(String id, String kafkaConsumerId, String topic, JsonNode payload,
                         List<String> capturedSqlIds) {
        this(id, kafkaConsumerId, topic, payload, capturedSqlIds, false);
    }
}
