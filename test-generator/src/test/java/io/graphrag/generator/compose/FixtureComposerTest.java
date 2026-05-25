package io.graphrag.generator.compose;

import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureComposerTest {

    @Test
    void buildsInsertFixtureForCapturedInsert() {
        CapturedSql insert = new CapturedSql(
                "sql-1", "path-1", CapturedSqlType.INSERT,
                "INSERT INTO users(id, name) VALUES (?, ?)",
                List.of(
                        new Binding(0, "u-1", BindingOrigin.COMPUTED, null),
                        new Binding(1, "John", BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of("users"), List.of("id", "name"));

        List<FixtureStatement> statements = FixtureComposer.fromCapturedSqls(List.of(insert));

        assertThat(statements).hasSize(1);
        FixtureStatement s = statements.get(0);
        assertThat(s.sql()).isEqualTo("INSERT INTO users(id, name) VALUES (?, ?)");
        assertThat(s.params()).containsExactly("u-1", "John");
        assertThat(s.affectedTable()).isEqualTo("users");
    }

    @Test
    void ignoresSelectStatements() {
        CapturedSql sel = new CapturedSql(
                "sql-1", "path-1", CapturedSqlType.SELECT,
                "SELECT * FROM users WHERE id = ?",
                List.of(new Binding(0, "u-1", BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of("users"), List.of("id"));

        List<FixtureStatement> statements = FixtureComposer.fromCapturedSqls(List.of(sel));

        // SELECT는 사전 데이터로 변환 (Phase 0: WHERE 절 파싱은 별개 작업, 일단 INSERT 기준만)
        // SELECT 자체는 fixture statement로 만들지 않음
        assertThat(statements).isEmpty();
    }

    @Test
    void cleanupStatementsAreFkReverseOrder() {
        CapturedSql usersInsert = capturedInsert("users", "id", "u-1");
        CapturedSql ordersInsert = capturedInsert("orders", "id", "o-1");

        List<FixtureStatement> cleanup = FixtureComposer.cleanupFor(
                List.of(usersInsert, ordersInsert));

        // FK 역순: orders(자식) → users(부모)
        assertThat(cleanup).hasSize(2);
        assertThat(cleanup.get(0).affectedTable()).isEqualTo("orders");
        assertThat(cleanup.get(1).affectedTable()).isEqualTo("users");
        assertThat(cleanup.get(0).sql()).startsWith("DELETE FROM orders");
    }

    private static CapturedSql capturedInsert(String table, String col, Object val) {
        return new CapturedSql(
                "sql-" + table, "path-1", CapturedSqlType.INSERT,
                "INSERT INTO " + table + "(" + col + ") VALUES (?)",
                List.of(new Binding(0, val, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of(table), List.of(col));
    }
}
