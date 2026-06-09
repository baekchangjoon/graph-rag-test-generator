package io.graphrag.model;

public record ForeignKey(
        String column,
        String referencedTable,
        String referencedColumn) {
}
