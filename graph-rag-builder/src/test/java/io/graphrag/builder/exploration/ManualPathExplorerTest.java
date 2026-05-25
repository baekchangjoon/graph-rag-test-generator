package io.graphrag.builder.exploration;

import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPathExplorerTest {

    private final Endpoint endpoint = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo-sut", "OrdersController", "create", false, List.of());

    @Test
    void identifiesSelfAsManual() {
        ManualPathExplorer explorer = new ManualPathExplorer(List.of());
        assertThat(explorer.name()).isEqualTo("manual");
    }

    @Test
    void proposesAllSeedsWhenBudgetIsLarge() {
        List<SampleInput> seeds = List.of(
                bodyInput(Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                bodyInput(Map.of("userId", "u-1", "amount", 0, "type", "EXPRESS")),
                bodyInput(Map.of("userId", "nonexistent", "amount", 100, "type", "STANDARD")));

        ManualPathExplorer explorer = new ManualPathExplorer(seeds);

        List<SampleInput> proposed = explorer.proposeInputs(endpoint,
                new ExplorationBudget(10, Duration.ofSeconds(60)));

        assertThat(proposed).hasSize(3);
        assertThat(proposed).containsExactlyElementsOf(seeds);
    }

    @Test
    void respectsMaxInputsBudget() {
        List<SampleInput> seeds = List.of(
                bodyInput(Map.of("amount", 1)),
                bodyInput(Map.of("amount", 2)),
                bodyInput(Map.of("amount", 3)),
                bodyInput(Map.of("amount", 4)));

        ManualPathExplorer explorer = new ManualPathExplorer(seeds);

        List<SampleInput> proposed = explorer.proposeInputs(endpoint,
                new ExplorationBudget(2, Duration.ofSeconds(60)));

        assertThat(proposed).hasSize(2);
        assertThat(proposed).containsExactly(seeds.get(0), seeds.get(1));
    }

    @Test
    void emptySeedsProducesEmptyList() {
        ManualPathExplorer explorer = new ManualPathExplorer(List.of());

        List<SampleInput> proposed = explorer.proposeInputs(endpoint,
                new ExplorationBudget(5, Duration.ofSeconds(60)));

        assertThat(proposed).isEmpty();
    }

    @Test
    void factoryFromBodyMapsBuildsSimpleInputs() {
        ManualPathExplorer explorer = ManualPathExplorer.fromBodies(List.of(
                Map.of("amount", 100, "type", "EXPRESS"),
                Map.of("amount", 0, "type", "EXPRESS")));

        List<SampleInput> proposed = explorer.proposeInputs(endpoint,
                new ExplorationBudget(10, Duration.ofSeconds(60)));

        assertThat(proposed).hasSize(2);
        assertThat(proposed.get(0).body())
                .isInstanceOfSatisfying(Map.class, m -> assertThat(m).containsEntry("amount", 100));
    }

    private static SampleInput bodyInput(Map<String, Object> body) {
        return new SampleInput(Map.of(), Map.of(), Map.of(), body);
    }
}
