package io.graphrag.builder.store;

import io.graphrag.model.GraphAsset;

/** 그래프 영속 추상화. Phase 0: JSON 파일. Phase 1+: Neo4j 등으로 교체 가능. */
public interface GraphStore {

    void save(GraphAsset asset);

    GraphAsset load();
}
