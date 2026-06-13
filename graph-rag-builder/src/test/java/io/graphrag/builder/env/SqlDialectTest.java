package io.graphrag.builder.env;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlDialectTest {

    @Test
    void postgresUsesOnConflict() {
        String sql = SqlDialect.idempotentInsert(DbConfig.Type.POSTGRES,
                "orders", java.util.List.of("id", "user_id"));
        assertThat(sql).isEqualTo(
                "INSERT INTO orders (id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
    }

    @Test
    void mysqlUsesInsertIgnore() {
        String sql = SqlDialect.idempotentInsert(DbConfig.Type.MYSQL,
                "orders", java.util.List.of("id", "user_id"));
        assertThat(sql).isEqualTo(
                "INSERT IGNORE INTO orders (id, user_id) VALUES (?, ?)");
    }
}
