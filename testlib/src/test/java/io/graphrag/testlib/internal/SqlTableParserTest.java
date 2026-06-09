package io.graphrag.testlib.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableParserTest {

    @Test
    void insert_extractsTableAndFirstColumnKey() {
        SqlTableParser.RowRef ref = SqlTableParser.parse(
                "INSERT INTO users(id, name) VALUES (?, ?)", new Object[]{"t-1-user", "John"});
        assertThat(ref.table()).isEqualTo("users");
        assertThat(ref.keyColumn()).isEqualTo("id");
        assertThat(ref.keyValue()).isEqualTo("t-1-user");
        assertThat(ref.kind()).isEqualTo(SqlTableParser.Kind.INSERT);
    }

    @Test
    void delete_extractsTableAndWhereKey() {
        SqlTableParser.RowRef ref = SqlTableParser.parse(
                "DELETE FROM orders WHERE user_id = ?", new Object[]{"t-1-user"});
        assertThat(ref.table()).isEqualTo("orders");
        assertThat(ref.keyColumn()).isEqualTo("user_id");
        assertThat(ref.keyValue()).isEqualTo("t-1-user");
        assertThat(ref.kind()).isEqualTo(SqlTableParser.Kind.DELETE);
    }

    @Test
    void otherStatements_returnNull() {
        assertThat(SqlTableParser.parse("UPDATE users SET name=? WHERE id=?",
                new Object[]{"a", "b"})).isNull();
    }
}
