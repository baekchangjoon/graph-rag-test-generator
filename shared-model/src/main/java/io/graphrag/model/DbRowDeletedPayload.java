package io.graphrag.model;

import java.util.Objects;

/**
 * {@link DashboardEventType#DB_ROW_DELETED} payload.
 */
public record DbRowDeletedPayload(String table, String keyColumn, String keyValue) {
    public DbRowDeletedPayload {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(keyColumn, "keyColumn");
        Objects.requireNonNull(keyValue, "keyValue");
    }
}
