package io.graphrag.fixture.multientity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ProvenanceIndexerIT 픽스처 — 서로 다른 두 엔티티 중 하나. */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    private String id;

    public String getId() {
        return id;
    }
}
