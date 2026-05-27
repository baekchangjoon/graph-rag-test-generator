package io.graphrag.builder.capture;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlType;
import io.jdbcintercept.api.BindingValue;
import io.jdbcintercept.api.CapturedQuery;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAgentBaggageBridgeTest {

    private final JdbcAgentBaggageBridge bridge = new JdbcAgentBaggageBridge();

    @BeforeEach @AfterEach
    void cleanRegistry() { CaptureContextRegistry.clearAll(); }

    @Test
    void routes_capturedQuery_to_registered_context_via_correlationId() {
        CaptureContext ctx = new CaptureContext("p-1");
        CaptureContextRegistry.register("p-1", ctx);

        CapturedQuery q = new CapturedQuery(
                "INSERT INTO t VALUES (?, ?)",
                List.of(new BindingValue(1, 42, null), new BindingValue(2, "x", null)),
                Optional.empty(), 0L, 0L, Optional.empty(), "p-1");
        bridge.afterQuery(q);

        assertThat(ctx.capturedSql()).hasSize(1);
        CapturedSql c = ctx.capturedSql().get(0);
        assertThat(c.type()).isEqualTo(CapturedSqlType.INSERT);
        assertThat(c.rawSql()).isEqualTo("INSERT INTO t VALUES (?, ?)");
        assertThat(c.bindings()).extracting("value").containsExactly(42, "x");
        assertThat(c.affectedTables()).containsExactly("t");
    }

    @Test
    void maps_snapshot_rows_to_readResultRows_for_select() {
        CaptureContext ctx = new CaptureContext("sel-1");
        CaptureContextRegistry.register("sel-1", ctx);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1);
        row.put("NAME", "George");

        CapturedQuery q = new CapturedQuery(
                "SELECT * FROM owners WHERE id = ?",
                List.of(new BindingValue(1, 1, null)),
                Optional.empty(), 0L, 0L, Optional.empty(),
                "sel-1",
                List.of(row));
        bridge.afterQuery(q);

        CapturedSql c = ctx.capturedSql().get(0);
        assertThat(c.type()).isEqualTo(CapturedSqlType.SELECT);
        assertThat(c.readResultRows()).hasSize(1);
        assertThat(c.readResultRows().get(0))
            .containsEntry("ID", 1)
            .containsEntry("NAME", "George");
    }

    @Test
    void falls_back_to_otel_baggage_when_correlationId_missing() {
        CaptureContext ctx = new CaptureContext("baggage-path");
        CaptureContextRegistry.register("baggage-path", ctx);

        Baggage baggage = Baggage.builder()
                .put(JdbcAgentBaggageBridge.BAGGAGE_KEY, "baggage-path")
                .build();
        try (Scope s = baggage.makeCurrent()) {
            // sanity — direct API sees the value
            assertThat(Baggage.current().getEntryValue(JdbcAgentBaggageBridge.BAGGAGE_KEY))
                .isEqualTo("baggage-path");
            // sanity — bridge's reflection path also sees it
            assertThat(JdbcAgentBaggageBridge.readBaggageReflectively(
                    JdbcAgentBaggageBridge.BAGGAGE_KEY)).isEqualTo("baggage-path");

            CapturedQuery q = new CapturedQuery(
                    "SELECT 1",
                    List.of(),
                    Optional.empty(), 0L, 0L, Optional.empty(),
                    "");   // empty correlationId → bridge looks at baggage
            bridge.afterQuery(q);
        }

        assertThat(ctx.capturedSql()).hasSize(1);
    }

    @Test
    void ignores_capture_when_no_matching_context() {
        CapturedQuery q = new CapturedQuery(
                "SELECT 1", List.of(), Optional.empty(), 0L, 0L, Optional.empty(), "no-such-path");
        bridge.afterQuery(q);   // should not throw
        assertThat(CaptureContextRegistry.size()).isZero();
    }

    @Test
    void serviceLoader_file_lists_bridge() throws Exception {
        // ServiceLoader 자동 등록 검증 — agent 측 ListenerRegistry 가 ServiceLoader 로 로드
        var loader = java.util.ServiceLoader.load(io.jdbcintercept.api.JdbcCaptureListener.class,
                getClass().getClassLoader());
        boolean found = loader.stream().anyMatch(p -> p.type().equals(JdbcAgentBaggageBridge.class));
        assertThat(found).isTrue();
    }
}
