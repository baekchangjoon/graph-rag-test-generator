package sample.reservation;

import javax.persistence.*;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "amount")
    private int amount;

    @Column(name = "created_at")
    private Long createdAt = System.currentTimeMillis();

    protected Reservation() {}

    public Reservation(Long orderId, String userId, int amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public Long getId() { return id; }
}
