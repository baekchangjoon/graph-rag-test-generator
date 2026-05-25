package io.graphrag.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 32)
    private String status;

    protected OrderEntity() {}

    public OrderEntity(String id, String userId, long amount, String type, String status) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.type = type;
        this.status = status;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public long getAmount() { return amount; }
    public String getType() { return type; }
    public String getStatus() { return status; }
}
