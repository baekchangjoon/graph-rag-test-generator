package io.graphrag.builder.capture;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureContextTest {

    @AfterEach
    void cleanup() { CaptureContext.clear(); }

    @Test
    void currentIsNullWhenNotSet() {
        assertThat(CaptureContext.current()).isNull();
    }

    @Test
    void setAndCurrentReturnsSameInstance() {
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);
        assertThat(CaptureContext.current()).isSameAs(ctx);
    }

    @Test
    void clearRemovesContext() {
        CaptureContext.set(new CaptureContext("path-1"));
        CaptureContext.clear();
        assertThat(CaptureContext.current()).isNull();
    }

    @Test
    void capturedSqlAccumulatesInsertedItems() {
        CaptureContext ctx = new CaptureContext("path-1");
        ctx.addCapturedSql(CapturedSqlBuilder.build(
                "path-1", "SELECT 1", List.of(), CapturedSqlSource.JPA_ENTITYMANAGER));
        ctx.addCapturedSql(CapturedSqlBuilder.build(
                "path-1", "INSERT INTO orders VALUES (?)", List.of(1L),
                CapturedSqlSource.JPA_ENTITYMANAGER));

        List<CapturedSql> all = ctx.capturedSql();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).rawSql()).contains("SELECT");
        assertThat(all.get(1).rawSql()).contains("INSERT");
    }

    @Test
    void capturedSqlReturnsImmutableSnapshot() {
        CaptureContext ctx = new CaptureContext("p");
        List<CapturedSql> snapshot1 = ctx.capturedSql();
        ctx.addCapturedSql(CapturedSqlBuilder.build(
                "p", "SELECT 1", List.of(), CapturedSqlSource.JPA_ENTITYMANAGER));

        assertThat(snapshot1).isEmpty();   // 이전 snapshot은 변경 안 됨
        assertThat(ctx.capturedSql()).hasSize(1);
    }
}
