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

/** Bug 1: 비-GET by-id가 유효 id + 리소스 시드를 갖는지(이전엔 sentinel "0" → service 미진입). */
class HappyInputByIdTest {

    @Test
    void putByIdEndpoint_getsValidPositiveIdAndResourceSeed() {
        Endpoint put = new Endpoint("put-api-orders-id", "PUT", "/api/orders/{id}", "x.C", "update",
                List.of(new EndpointParam("id", "int", ParamKind.PATH)), false);
        TableSchema orders = new TableSchema("orders",
                List.of(new ColumnSchema("id", "INT", false, true)), List.of(), List.of());

        SynthesizedInput out = EndpointExplorationRunner.happyInput(
                put, null, List.of(orders), Map.of(), Map.of(), Map.of());

        assertThat(out.body().get("id").asInt()).isGreaterThan(0);   // sentinel 0 아님
        assertThat(out.seeds()).isNotEmpty();                        // 리소스 행 시드됨
        assertThat(out.seeds().get(0).table()).isEqualTo("orders");
    }
}
