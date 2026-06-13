package io.graphrag.builder.run;

import io.graphrag.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReadInputSynthesizerTest {

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
        assertThat(seed.values()).contains("1");
    }
}
