package io.graphrag.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Kafka 발행 이벤트 캡처.
 *
 * <p>{@code nonDeterministicValues}: REQ-012 2회-발행 diff로 검출된 비결정 값 집합.
 * UUID/ISO-8601 휴리스틱(REQ-009/011)이 놓치는 비-패턴 서버 생성 값(시퀀스 ID 등)을 포함한다.
 * Generator의 {@code deterministicPayload}가 이 값을 제거/형식단언 대상으로 처리한다.
 * 후방 호환: 미지정 시 빈 집합.
 */
public record CapturedEventEmit(
        String id,
        String pathId,
        String topic,
        String key,
        JsonNode payload,
        Set<String> nonDeterministicValues
) {

    /** 후방 호환 5-arg constructor (REQ-012 이전 그래프용). */
    public CapturedEventEmit(String id, String pathId, String topic, String key, JsonNode payload) {
        this(id, pathId, topic, key, payload, Set.of());
    }

    public CapturedEventEmit {
        nonDeterministicValues = nonDeterministicValues == null ? Set.of() : nonDeterministicValues;
    }
}
