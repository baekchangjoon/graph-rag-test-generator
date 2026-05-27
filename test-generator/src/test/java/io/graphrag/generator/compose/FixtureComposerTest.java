package io.graphrag.generator.compose;

import io.graphrag.model.Binding;
import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import io.graphrag.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void ignoresSelectStatementsWithoutReadResultRows() {
        CapturedSql sel = new CapturedSql(
                "sql-1", "path-1", CapturedSqlType.SELECT,
                "SELECT * FROM users WHERE id = ?",
                List.of(new Binding(0, "u-1", BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED,
                new SourceLocation("X", "y", 1),
                List.of("users"), List.of("id"));

        // readResultRows 없는 SELECT 는 fixture 생성 안 함
        assertThat(FixtureComposer.fromCapturedSqls(List.of(sel))).isEmpty();
    }

    @Test
    void selectWithReadResultRowsBecomesInsertFixture() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("first_name", "George");
        row.put("last_name", "Franklin");

        CapturedSql sel = new CapturedSql(
                "sql-sel", "p1", CapturedSqlType.SELECT,
                "SELECT first_name FROM owners WHERE id = ?",
                List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("OwnerRepo", "findById", 1),
                List.of("owners"), List.of(), List.of(row));

        List<FixtureStatement> stmts = FixtureComposer.fromCapturedSqls(List.of(sel));
        assertThat(stmts).hasSize(1);
        assertThat(stmts.get(0).sql())
                .isEqualTo("INSERT INTO owners (id, first_name, last_name) VALUES (?, ?, ?)");
        assertThat(stmts.get(0).params()).containsExactly(1, "George", "Franklin");
        assertThat(stmts.get(0).affectedTable()).isEqualTo("owners");
    }

    @Test
    void multipleSnapshotRowsBecomeMultipleInserts() {
        CapturedSql sel = new CapturedSql(
                "sql-sel", "p1", CapturedSqlType.SELECT,
                "SELECT * FROM owners WHERE id <= ?",
                List.of(new Binding(0, 10, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("X", "y", 1),
                List.of("owners"), List.of(),
                List.of(rowMap("id", 1, "name", "A"), rowMap("id", 2, "name", "B")));

        List<FixtureStatement> stmts = FixtureComposer.fromCapturedSqls(List.of(sel));
        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).params()).containsExactly(1, "A");
        assertThat(stmts.get(1).params()).containsExactly(2, "B");
    }

    @Test
    void duplicateSnapshotRowsDeduped() {
        // 같은 row (id=1) 가 두 SELECT 에서 snapshot 됨 — 한 번만 INSERT
        CapturedSql sel1 = newSelect("p1", List.of(rowMap("id", 1, "name", "A")));
        CapturedSql sel2 = newSelect("p1", List.of(rowMap("id", 1, "name", "A")));

        List<FixtureStatement> stmts = FixtureComposer.fromCapturedSqls(List.of(sel1, sel2));
        assertThat(stmts).hasSize(1);
    }

    @Test
    void cleanupForSelectSnapshotUsesIdColumn() {
        CapturedSql sel = newSelect("p1", List.of(rowMap("id", 1, "name", "A")));
        List<FixtureStatement> cleanup = FixtureComposer.cleanupFor(List.of(sel));
        assertThat(cleanup).hasSize(1);
        assertThat(cleanup.get(0).sql()).isEqualTo("DELETE FROM owners WHERE id = ?");
        assertThat(cleanup.get(0).params()).containsExactly(1);
    }

    @Test
    void cleanupForSnapshotWithoutIdUsesAllColumnsAnd() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "A");
        row.put("city", "Seoul");
        CapturedSql sel = newSelect("p1", List.of(row));
        List<FixtureStatement> cleanup = FixtureComposer.cleanupFor(List.of(sel));
        assertThat(cleanup.get(0).sql())
                .isEqualTo("DELETE FROM owners WHERE name = ? AND city = ?");
        assertThat(cleanup.get(0).params()).containsExactly("A", "Seoul");
    }

    private static Map<String, Object> rowMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static CapturedSql newSelect(String pathId, List<Map<String, Object>> rows) {
        return new CapturedSql(
                "sql-" + pathId, pathId, CapturedSqlType.SELECT,
                "SELECT * FROM owners WHERE id = ?",
                List.of(new Binding(0, 1, BindingOrigin.COMPUTED, null)),
                CapturedSqlSource.JPA_ENTITYMANAGER,
                new SourceLocation("X", "y", 1),
                List.of("owners"), List.of(), rows);
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
