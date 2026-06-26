package io.graphrag.builder.run;

import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GRB_SYNTH_EXCLUDE_METHODS(C): base happy 합성 skip → 빈 body·시드 없음. */
class HappyInputSynthesisExcludeTest {

    @Test
    void skipHappySynthesis_returnsEmptyBodyAndNoSeeds() {
        Endpoint put = new Endpoint("put-api-orders-id", "PUT", "/api/orders/{id}", "x.C", "update",
                List.of(new EndpointParam("id", "int", ParamKind.PATH)), false);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "INT", false, true)), List.of(), List.of());

        SynthesizedInput skipped = EndpointExplorationRunner.happyInput(
                put, null, List.of(orders), Map.of(), Map.of(), Map.of(),
                null, Map.of(), List.of(), Map.of(), true);
        SynthesizedInput normal = EndpointExplorationRunner.happyInput(
                put, null, List.of(orders), Map.of(), Map.of(), Map.of());

        assertThat(skipped.body().isEmpty()).isTrue();
        assertThat(skipped.seeds()).isEmpty();
        assertThat(normal.body().get("id").asInt()).isGreaterThan(0);
        assertThat(normal.seeds()).isNotEmpty();
    }
}
