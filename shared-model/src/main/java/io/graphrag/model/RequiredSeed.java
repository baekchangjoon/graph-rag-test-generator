package io.graphrag.model;

import java.util.List;

/** builder가 read-path 탐색을 위해 사전 삽입한 시드 행. generator가 그대로 재현한다. */
public record RequiredSeed(
        String id,
        String pathId,
        String table,
        List<String> columns,
        List<String> values) {

    public RequiredSeed {
        columns = columns == null ? List.of() : columns;
        values = values == null ? List.of() : values;
    }
}
