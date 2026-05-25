package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathTypesTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void pathConstraintConstructionAndJson() throws Exception {
        PathConstraint c = new PathConstraint(
                "userId > 0 && type.equals('EXPRESS')",
                List.of("userId", "type"));

        String json = mapper.writeValueAsString(c);
        PathConstraint back = mapper.readValue(json, PathConstraint.class);

        assertThat(back).isEqualTo(c);
        assertThat(json).contains("\"expression\":");
        assertThat(json).contains("\"variables\":[\"userId\",\"type\"]");
    }

    @Test
    void sampleInputConstructionAndJson() throws Exception {
        SampleInput input = new SampleInput(
                Map.of("X-Trace", "abc"),
                Map.of("id", "42"),
                Map.of("verbose", "true"),
                Map.of("amount", 100, "type", "EXPRESS"));

        String json = mapper.writeValueAsString(input);
        SampleInput back = mapper.readValue(json, SampleInput.class);

        assertThat(back.headers()).containsEntry("X-Trace", "abc");
        assertThat(back.pathParams()).containsEntry("id", "42");
        assertThat(back.queryParams()).containsEntry("verbose", "true");
        assertThat(json).contains("\"path_params\":");
        assertThat(json).contains("\"query_params\":");
    }

    @Test
    void sampleInputAcceptsEmptyMaps() throws Exception {
        SampleInput input = new SampleInput(Map.of(), Map.of(), Map.of(), null);

        String json = mapper.writeValueAsString(input);
        SampleInput back = mapper.readValue(json, SampleInput.class);

        assertThat(back.headers()).isEmpty();
        assertThat(back.body()).isNull();
    }

    @Test
    void exploredPathFullRoundTrip() throws Exception {
        ExploredPath p = new ExploredPath(
                "01HXG0M3FMXJ8N3F8B4N1V2W3X",
                "POST:/api/orders",
                PathExplorerKind.JDART,
                new SampleInput(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                new PathConstraint("amount > 0", List.of("amount")),
                List.of("OrderService.placeOrder:142", "OrderRepository.save:55"),
                201,
                Map.of("orderId", "o-1", "status", "PENDING"),
                "cov-sig-hash",
                "abc1234");

        String json = mapper.writeValueAsString(p);
        ExploredPath back = mapper.readValue(json, ExploredPath.class);

        assertThat(back.id()).isEqualTo(p.id());
        assertThat(back.endpointId()).isEqualTo(p.endpointId());
        assertThat(back.discoveredBy()).isEqualTo(PathExplorerKind.JDART);
        assertThat(back.branchesTaken()).containsExactly(
                "OrderService.placeOrder:142", "OrderRepository.save:55");
        assertThat(back.exitStatus()).isEqualTo(201);
        assertThat(back.codeVersion()).isEqualTo("abc1234");
        assertThat(json).contains("\"discovered_by\":\"JDART\"");
        assertThat(json).contains("\"branches_taken\":");
        assertThat(json).contains("\"exit_status\":201");
        assertThat(json).contains("\"coverage_signature\":");
    }

    @Test
    void exploredPathAllowsNullPathConstraint() throws Exception {
        ExploredPath p = new ExploredPath(
                "id1",
                "endpoint1",
                PathExplorerKind.FUZZER,
                new SampleInput(Map.of(), Map.of(), Map.of(), null),
                null,
                List.of(),
                200,
                null,
                "sig",
                "ver");

        String json = mapper.writeValueAsString(p);
        ExploredPath back = mapper.readValue(json, ExploredPath.class);

        assertThat(back.pathConstraint()).isNull();
        assertThat(back.exitResponseShape()).isNull();
    }
}
