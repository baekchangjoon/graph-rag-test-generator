package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ArrayBodyMutationIntegrationTest {

    @Test
    void elementFieldMutationsAppliedToArrayBody() {
        ArrayNode base = Json.mapper().createArrayNode();
        base.add(Json.mapper().createObjectNode().put("userId", "u1").put("amount", 5));
        List<BodyShape.BodyField> fields = List.of(
                new BodyShape.BodyField("userId", "java.lang.String"),
                new BodyShape.BodyField("amount", "java.lang.Integer"));

        List<JsonNode> seen = new ArrayList<>();
        EndpointInvoker invoker = body -> {
            seen.add(body.deepCopy());
            return new InvocationOutcome(200, null, Set.of(), 0, 0);
        };

        Endpoint endpoint = new Endpoint("e", "POST", "/api/orders/batch", null, null,
                List.of(), false);
        EndpointTarget target = new EndpointTarget(endpoint, base, fields, List.of(), invoker);

        new HeuristicExplorer().explore(target,
                new ExplorationBudget(100, Duration.ofMinutes(1)),
                new KnownCoverage());

        // happy(원본) 외에, element[0].amount를 0으로 만든 변이가 실제로 호출됐는지
        boolean zeroAmount = seen.stream().anyMatch(b ->
                b.isArray() && b.size() == 1 && b.get(0).path("amount").asInt(-1) == 0);
        boolean emptyArray = seen.stream().anyMatch(b -> b.isArray() && b.isEmpty());
        assertThat(zeroAmount).as("element[0] amount 변이").isTrue();
        assertThat(emptyArray).as("empty-array 변이").isTrue();
    }
}
