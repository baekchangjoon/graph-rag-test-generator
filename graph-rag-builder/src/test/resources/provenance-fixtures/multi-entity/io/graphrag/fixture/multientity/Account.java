package io.graphrag.fixture.multientity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ProvenanceIndexerIT 픽스처 — 서로 다른 두 엔티티 중 나머지. */
@Entity
@Table(name = "fund_account")
public class Account {

    @Id
    private String id;

    @Column(name = "balance_amount")
    private long balance;

    public String getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }
}
