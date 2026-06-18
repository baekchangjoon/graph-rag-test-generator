package sample.ledger;

import javax.persistence.*;

@Entity @Table(name = "ledger_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = "order_id"))
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "user_id") private String userId;
    @Column(name = "amount") private int amount;
    @Column(name = "created_at") private Long createdAt = System.currentTimeMillis();
    protected LedgerEntry() {}
    public LedgerEntry(Long orderId, String userId, int amount) {
        this.orderId = orderId; this.userId = userId; this.amount = amount;
    }
}
