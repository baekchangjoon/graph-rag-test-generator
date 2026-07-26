package io.graphrag.fixture.jpainherited;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ProvenanceIndexerIT 픽스처 — jpa-override와 달리 findById를 재선언하지 않는 순정 JpaRepository 관례. */
@Entity
@Table(name = "fund_accounts")
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
