package io.graphrag.builder.cli;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.KafkaExchange;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalBuildPlannerEndpointTest {

    @Test
    void exploresOnlySelectedAndCarriesRestInclKafkaAndSeeds() {
        Endpoint a = endpoint("ep-a");
        Endpoint b = endpoint("ep-b");
        // pb (carried) references a seed via requiredSeedIds; seed row must be carried too
        ExploredPath pa = path("p-a", "ep-a", List.of());
        ExploredPath pb = path("p-b", "ep-b", List.of("seed-b"));
        RequiredSeed seedB = new RequiredSeed("seed-b", "p-b", "t", List.of("id"), List.of("1"));
        // kafka exchange (carried) owns SQL via capturedSqlIds (NOT pathId)
        KafkaExchange kx = kafka("kx-1", "kc-1", List.of("ksql-1"));
        CapturedSql ksql = capturedSql("ksql-1", null); // pathId null — linked only via capturedSqlIds
        GraphAsset base = asset(List.of(a, b), List.of(pa, pb),
                List.of(kx), List.of(ksql), List.of(seedB));

        IncrementalPlan plan = new IncrementalBuildPlanner().planForEndpoints(
                base, Set.of("ep-a"), List.of(a, b), List.of(),
                List.of(kafkaConsumer("kc-1")));

        assertTrue(plan.shouldExplore("ep-a"));
        assertFalse(plan.shouldExplore("ep-b"));
        assertTrue(plan.carriedPaths().stream().anyMatch(p -> p.id().equals("p-b")));
        assertFalse(plan.carriedPaths().stream().anyMatch(p -> p.id().equals("p-a")));
        assertEquals(1, plan.carriedKafkaExchanges().size());
        // Kafka SQL carried via capturedSqlIds, not pathId
        assertTrue(plan.carriedSql().stream().anyMatch(s -> s.id().equals("ksql-1")));
        // RequiredSeed for carried path carried too
        assertTrue(plan.carriedSeeds().stream().anyMatch(s -> s.id().equals("seed-b")));
    }

    @Test
    void noBaseProducesEmptyCarry() {
        IncrementalPlan plan = new IncrementalBuildPlanner().planForEndpoints(
                null, Set.of("ep-a"), List.of(endpoint("ep-a")), List.of(), List.of());
        assertTrue(plan.shouldExplore("ep-a"));
        assertTrue(plan.carriedPaths().isEmpty() && plan.carriedSeeds().isEmpty()
                && plan.carriedKafkaExchanges().isEmpty());
    }

    private static Endpoint endpoint(String id) {
        return new Endpoint(id, "POST", "/" + id, "Handler", "handle", List.of(), false);
    }

    private static ExploredPath path(String id, String endpointId, List<String> requiredSeedIds) {
        return new ExploredPath(id, endpointId, null, 200, null,
                List.of(), List.of(), List.of(), "test", List.of(), List.of(), requiredSeedIds);
    }

    private static KafkaExchange kafka(String id, String consumerId, List<String> sqlIds) {
        return new KafkaExchange(id, consumerId, "topic", null, sqlIds, false);
    }

    private static KafkaConsumer kafkaConsumer(String id) {
        return new KafkaConsumer(id, "topic", "group", "Handler", "onMessage", "Payload");
    }

    private static CapturedSql capturedSql(String id, String pathId) {
        return new CapturedSql(id, pathId, "SELECT", "select 1", "t", List.of());
    }

    private static GraphAsset asset(List<Endpoint> endpoints, List<ExploredPath> paths,
            List<KafkaExchange> kafkaExch, List<CapturedSql> sql, List<RequiredSeed> seeds) {
        return new GraphAsset("sut", "sha", endpoints, paths, sql,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), kafkaExch, seeds);
    }
}
