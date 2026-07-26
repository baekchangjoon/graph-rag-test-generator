package io.graphrag.fixture.exists;

/** ProvenanceIndexerIT 픽스처 — findById가 반환하는 엔티티(DB_READ 태깅은 후속 task 범위). */
public class Account {

    private final String id;

    public Account(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
