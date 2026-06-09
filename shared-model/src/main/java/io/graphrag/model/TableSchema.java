package io.graphrag.model;

import java.util.List;

/** 운영 DBMS 기준 물리 스키마 사실. */
public record TableSchema(
        String name,
        List<ColumnSchema> columns,
        List<ForeignKey> foreignKeys,
        List<List<String>> uniqueKeys) {
}
