package io.graphrag.builder.env;

import java.util.List;

/** dialect별 SQL 조각. 현재는 멱등 INSERT만. */
public final class SqlDialect {

    private SqlDialect() {
    }

    public static String idempotentInsert(DbConfig.Type type, String table, List<String> columns) {
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        return switch (type) {
            case POSTGRES -> "INSERT INTO " + table + " (" + cols + ") VALUES ("
                    + placeholders + ") ON CONFLICT DO NOTHING";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES ("
                    + placeholders + ")";
        };
    }
}
