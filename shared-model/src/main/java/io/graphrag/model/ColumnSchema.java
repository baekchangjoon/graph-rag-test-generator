package io.graphrag.model;

public record ColumnSchema(
        String name,
        String jdbcType,
        boolean nullable,
        boolean primaryKey) {
}
