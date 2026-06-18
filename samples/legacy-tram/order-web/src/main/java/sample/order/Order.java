package sample.order;

import javax.persistence.*;

@Entity @Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "amount") private int amount;
    @Column(name = "created_at") private Long createdAt = System.currentTimeMillis();
    protected Order() {}
    public Order(String userId, int amount) { this.userId = userId; this.amount = amount; }
    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
