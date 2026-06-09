package io.graphrag.sample.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    protected Order() {
    }

    public Order(User user, Integer amount, String type, String status) {
        this.user = user;
        this.amount = amount;
        this.type = type;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Integer getAmount() {
        return amount;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }
}
