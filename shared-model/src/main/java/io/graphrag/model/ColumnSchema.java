package io.graphrag.model;

public record ColumnSchema(
        String name,
        String jdbcType,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement) {

    /** autoIncrement 미지정 시 false (구버전 그래프/픽스처 호환). */
    public ColumnSchema(String name, String jdbcType, boolean nullable, boolean primaryKey) {
        this(name, jdbcType, nullable, primaryKey, false);
    }
}
