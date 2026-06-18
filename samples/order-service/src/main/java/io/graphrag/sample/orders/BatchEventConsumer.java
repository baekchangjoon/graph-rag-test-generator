package io.graphrag.sample.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Collection Kafka payload 회귀 가드: 배치 토픽에서 List&lt;Item&gt; 페이로드를 받아 각 항목을
 * order_events 행으로 기록한다. {@link OrderEventConsumer} 패턴을 미러링하되 collection 페이로드다.
 * broker(spring.kafka.bootstrap-servers)가 설정될 때만 활성 — broker 없는 e2e/통합 부팅에 무영향.
 */
@Component
@ConditionalOnProperty("spring.kafka.bootstrap-servers")
public class BatchEventConsumer {

    public record Item(String eventId, String type, String userId) {
    }

    private final OrderEventRepository repository;

    public BatchEventConsumer(OrderEventRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "order.events.batch", groupId = "order-service-batch")
    public void onBatch(List<Item> items) {
        for (Item it : items) {
            if (it.eventId() == null || it.userId() == null) {
                continue;
            }
            if (!repository.existsById(it.eventId())) {
                repository.save(new OrderEvent(it.eventId(),
                        it.type() == null ? "UNKNOWN" : it.type(), it.userId()));
            }
        }
    }
}
