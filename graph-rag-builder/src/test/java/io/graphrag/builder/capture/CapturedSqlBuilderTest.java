package io.graphrag.builder.capture;

import io.graphrag.model.BindingOrigin;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import io.graphrag.model.CapturedSqlType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedSqlBuilderTest {

    @Test
    void detectsSelectType() {
        CapturedSql sql = CapturedSqlBuilder.build(
                "path-1",
                "SELECT * FROM users WHERE id = ?",
                List.of(42L),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED);

        assertThat(sql.type()).isEqualTo(CapturedSqlType.SELECT);
        assertThat(sql.rawSql()).contains("SELECT");
    }

    @Test
    void detectsInsertType() {
        CapturedSql sql = CapturedSqlBuilder.build(
                "path-1",
                "INSERT INTO orders(id, user_id) VALUES (?, ?)",
                List.of(1L, 42L),
                CapturedSqlSource.JPA_ENTITYMANAGER);

        assertThat(sql.type()).isEqualTo(CapturedSqlType.INSERT);
    }

    @Test
    void detectsUpdateAndDelete() {
        CapturedSql update = CapturedSqlBuilder.build("p", "UPDATE orders SET status=?",
                List.of("PENDING"), CapturedSqlSource.JPA_ENTITYMANAGER);
        CapturedSql delete = CapturedSqlBuilder.build("p", "DELETE FROM orders",
                List.of(), CapturedSqlSource.JPA_ENTITYMANAGER);

        assertThat(update.type()).isEqualTo(CapturedSqlType.UPDATE);
        assertThat(delete.type()).isEqualTo(CapturedSqlType.DELETE);
    }

    @Test
    void bindingsPreserveValuesWithDefaultOrigin() {
        CapturedSql sql = CapturedSqlBuilder.build(
                "p",
                "SELECT * FROM users WHERE id = ? AND status = ?",
                List.of(42L, "ACTIVE"),
                CapturedSqlSource.JPA_REPOSITORY_DERIVED);

        assertThat(sql.bindings()).hasSize(2);
        assertThat(sql.bindings().get(0).position()).isZero();
        assertThat(sql.bindings().get(0).value()).isEqualTo(42L);
        assertThat(sql.bindings().get(1).position()).isEqualTo(1);
        assertThat(sql.bindings().get(1).value()).isEqualTo("ACTIVE");
        // 초기 origin은 COMPUTED. Phase 1+에서 dataflow로 API_PARAM/LITERAL 보강
        assertThat(sql.bindings().get(0).origin()).isEqualTo(BindingOrigin.COMPUTED);
    }

    @Test
    void affectedTablesExtractedFromSql() {
        CapturedSql sql = CapturedSqlBuilder.build(
                "p",
                "INSERT INTO orders(id, user_id, status) VALUES (?, ?, ?)",
                List.of(1L, 42L, "PENDING"),
                CapturedSqlSource.JPA_ENTITYMANAGER);

        assertThat(sql.affectedTables()).contains("orders");
    }

    @Test
    void idAndPathIdAssigned() {
        CapturedSql sql = CapturedSqlBuilder.build(
                "path-xyz",
                "SELECT 1",
                List.of(),
                CapturedSqlSource.JPA_ENTITYMANAGER);

        assertThat(sql.pathId()).isEqualTo("path-xyz");
        assertThat(sql.id()).isNotBlank();
    }
}
