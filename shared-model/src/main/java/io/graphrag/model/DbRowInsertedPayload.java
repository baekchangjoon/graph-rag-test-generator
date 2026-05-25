package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#DB_ROW_INSERTED} payload.
 *
 * <p>{@link #keyValue()}는 보통 testId 기반 unique 키. cleanup 시 동일 키로 삭제.
 */
public record DbRowInsertedPayload(String table, String keyColumn, String keyValue) {
    public DbRowInsertedPayload {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(keyColumn, "keyColumn");
        Objects.requireNonNull(keyValue, "keyValue");
    }
}
