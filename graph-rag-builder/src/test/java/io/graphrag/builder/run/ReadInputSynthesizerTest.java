package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReadInputSynthesizerTest {

    @Test
    void fkColumnSeedsParentTableFirst() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", java.util.List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        TableSchema users = new TableSchema("users",
                java.util.List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("name", "VARCHAR", false, false)),
                java.util.List.of(), java.util.List.of());
        TableSchema orders = new TableSchema("orders",
                java.util.List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("user_id", "VARCHAR", false, false),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                java.util.List.of(new ForeignKey("user_id", "users", "id")),
                java.util.List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, java.util.List.of(orders, users));

        // parent users seeded before child orders
        java.util.List<String> tableOrder = out.seeds().stream()
                .map(SynthesizedInput.SeedRow::table).toList();
        assertThat(tableOrder).containsExactly("users", "orders");
        // child FK value == parent PK value
        SynthesizedInput.SeedRow users_ = out.seeds().get(0);
        SynthesizedInput.SeedRow orders_ = out.seeds().get(1);
        int fkIdx = orders_.columns().indexOf("user_id");
        int pkIdx = users_.columns().indexOf("id");
        assertThat(orders_.values().get(fkIdx)).isEqualTo(users_.values().get(pkIdx));
    }

    @Test
    void pathVariableSeedsTargetTableAndBuildsInput() {
        Endpoint endpoint = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
                "x.C", "get", List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)),
                true);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "BIGINT", false, true),
                        new ColumnSchema("status", "VARCHAR", false, false)),
                List.of(), List.of());

        SynthesizedInput out = new ReadInputSynthesizer().synthesize(endpoint, List.of(orders));

        assertThat(out.body().get("id").asText()).isEqualTo("1");
        assertThat(out.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = out.seeds().get(0);
        assertThat(seed.table()).isEqualTo("orders");
        assertThat(seed.columns()).contains("id");
        // bigint PK 컬럼이므로 seed 값은 문자열이 아니라 Long으로 강제된다 (varchar→bigint INSERT 방지)
        assertThat(seed.values()).contains(1L);
    }
}
