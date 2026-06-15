package io.graphrag.sample.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Kafka consumer가 기록하는 주문 이벤트 감사 행 (시드 타깃 해석/Kafka 회귀 가드). */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    @Id
    private String id;

    @Column(nullable = false)
    private String type;

    @Column(name = "user_id", nullable = false)
    private String userId;

    protected OrderEvent() {
    }

    public OrderEvent(String id, String type, String userId) {
        this.id = id;
        this.type = type;
        this.userId = userId;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getUserId() {
        return userId;
    }
}
