package io.graphrag.model;

/**
 * @KafkaListener consumer 메서드 1개 (정적 인덱싱).
 * 빌더는 {@code topic}에 {@code payloadType} 형태의 유효 이벤트를 발행해 consumer를 탐색한다.
 *
 * @param topic       리스닝 토픽(리터럴 또는 미해석 ${prop} 표현식)
 * @param payloadType 역직렬화 대상 이벤트 타입 FQN (첫 @Payload 파라미터)
 */
public record KafkaConsumer(
        String id,
        String topic,
        String groupId,
        String handlerClass,
        String handlerMethod,
        String payloadType) {
}
