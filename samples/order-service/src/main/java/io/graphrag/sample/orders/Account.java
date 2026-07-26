package io.graphrag.sample.orders;

import jakarta.persistence.*;

/** transfers fixture의 계좌 엔티티. String 자연키(REQ-004 @Table/@Column 오버라이드 검증용). */
@Entity
@Table(name = "fund_accounts")
public class Account {

    @Id
    private String id;

    @Column(name = "balance_amount", nullable = false)
    private long balance;

    public String getId() { return id; }
    public long getBalance() { return balance; }
}
