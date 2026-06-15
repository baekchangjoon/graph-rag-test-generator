package io.graphrag.sample.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer 회귀 가드. raw String 메시지를 내부에서 OrderEventPayload로 역직렬화(빌더의
 * readValue-target 추출 경로 검증)하고 order_events 행을 기록한다.
 * broker(spring.kafka.bootstrap-servers)가 설정될 때만 활성 — broker 없는 e2e/통합 부팅에 무영향.
 */
@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class OrderEventConsumer {

    public record OrderEventPayload(String eventId, String type, String userId) {
    }

    private final OrderEventRepository repository;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(OrderEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order.events", groupId = "order-service")
    public void onOrderEvent(String message) {
        try {
            OrderEventPayload event = objectMapper.readValue(message, OrderEventPayload.class);
            if (event.eventId() == null || event.userId() == null) {
                return;
            }
            if (!repository.existsById(event.eventId())) {
                repository.save(new OrderEvent(event.eventId(),
                        event.type() == null ? "UNKNOWN" : event.type(), event.userId()));
            }
        } catch (Exception e) {
            System.err.println("[OrderEventConsumer] parse error: " + e.getMessage());
        }
    }
}
