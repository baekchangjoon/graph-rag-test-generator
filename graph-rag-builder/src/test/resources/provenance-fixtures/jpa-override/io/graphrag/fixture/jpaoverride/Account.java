package io.graphrag.fixture.jpaoverride;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ProvenanceIndexerIT 픽스처 — @Table/@Column 오버라이드가 있는 JPA 엔티티(REQ-004). */
@Entity
@Table(name = "fund_accounts")
public class Account {

    @Id
    private Long id;

    @Column(name = "balance_amount")
    private long balance;

    public Account(Long id, long balance) {
        this.id = id;
        this.balance = balance;
    }

    public Long getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }
}
