package io.graphrag.builder.cli;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalBuildPlannerTest {

    private static final Endpoint ORDERS = new Endpoint("post-api-orders", "POST",
            "/api/orders", "io.graphrag.sample.orders.OrderController", "create",
            List.of(), false);
    private static final Endpoint USERS = new Endpoint("post-api-users", "POST",
            "/api/users", "io.graphrag.sample.users.UserController", "create",
            List.of(), false);
    private static final WsEndpoint WS_ORDERS = new WsEndpoint("ws-orders-count", "/ws",
            "/app", "/app/orders/count", "/topic/orders",
            "io.graphrag.sample.orders.OrderCountWsController", "count", "CountRequest");

    private static GraphAsset previous() {
        return new GraphAsset("order-service", "sha-prev",
                List.of(ORDERS, USERS),
                List.of(path("p-orders-1", "post-api-orders"),
                        path("p-users-1", "post-api-users")),
                List.of(sql("sql-orders-1", "p-orders-1"),
                        sql("sql-users-1", "p-users-1"),
                        sql("sql-ws-1", "wsx-1")),
                List.of(), List.of(),
                List.of(new CapturedHttpCall("http-1", "p-orders-1", "GET", "/inventory",
                        Map.of(), null, 200, "{}", List.of(), true)),
                List.of(WS_ORDERS),
                List.of(new WsExchange("wsx-1", "ws-orders-count", null, "/topic/orders",
                        null, List.of("sql-ws-1"))));
    }

    private static ExploredPath path(String id, String endpointId) {
        return new ExploredPath(id, endpointId, null, 200, null, List.of(), List.of(),
                List.of(), "heuristic", List.of(), List.of());
    }

    private static CapturedSql sql(String id, String pathId) {
        return new CapturedSql(id, pathId, "SELECT", "SELECT 1", "orders", List.of());
    }

    @Test
    void cleanPartitionIsCarriedOver_dirtyPartitionIsReExplored() {
        IncrementalPlan plan = new IncrementalBuildPlanner().plan(previous(),
                List.of("src/main/java/io/graphrag/sample/users/UserController.java"),
                List.of(ORDERS, USERS), List.of(WS_ORDERS));

        assertThat(plan.shouldExplore("post-api-users")).isTrue();
        assertThat(plan.shouldExplore("post-api-orders")).isFalse();
        assertThat(plan.shouldExplore("ws-orders-count")).isFalse();

        assertThat(plan.carriedPaths()).extracting(ExploredPath::id)
                .containsExactly("p-orders-1");
        assertThat(plan.carriedSql()).extracting(CapturedSql::id)
                .containsExactlyInAnyOrder("sql-orders-1", "sql-ws-1");
        assertThat(plan.carriedHttpCalls()).extracting(CapturedHttpCall::id)
                .containsExactly("http-1");
        assertThat(plan.carriedWsExchanges()).extracting(WsExchange::id)
                .containsExactly("wsx-1");
    }

    @Test
    void deletedEndpointFactsAreNotCarried() {
        IncrementalPlan plan = new IncrementalBuildPlanner().plan(previous(),
                List.of("src/main/java/io/graphrag/sample/users/UserController.java"),
                List.of(USERS), List.of());

        assertThat(plan.carriedPaths()).isEmpty();
        assertThat(plan.carriedSql()).isEmpty();
        assertThat(plan.carriedHttpCalls()).isEmpty();
        assertThat(plan.carriedWsExchanges()).isEmpty();
    }

    @Test
    void endpointWithoutPreviousExplorationIsExploredEvenIfPartitionClean() {
        Endpoint newInOrders = new Endpoint("post-api-orders-cancel", "POST",
                "/api/orders/cancel", "io.graphrag.sample.orders.OrderController",
                "cancel", List.of(), false);

        IncrementalPlan plan = new IncrementalBuildPlanner().plan(previous(),
                List.of("src/main/java/io/graphrag/sample/users/UserController.java"),
                List.of(ORDERS, USERS, newInOrders), List.of(WS_ORDERS));

        assertThat(plan.shouldExplore("post-api-orders-cancel")).isTrue();
        assertThat(plan.shouldExplore("post-api-orders")).isFalse();
    }

    @Test
    void unmappableChangeReExploresEverything() {
        IncrementalPlan plan = new IncrementalBuildPlanner().plan(previous(),
                List.of("src/main/resources/mappers/OrderMapper.xml"),
                List.of(ORDERS, USERS), List.of(WS_ORDERS));

        assertThat(plan.shouldExplore("post-api-orders")).isTrue();
        assertThat(plan.shouldExplore("post-api-users")).isTrue();
        assertThat(plan.shouldExplore("ws-orders-count")).isTrue();
        assertThat(plan.carriedPaths()).isEmpty();
        assertThat(plan.carriedWsExchanges()).isEmpty();
    }

    @Test
    void exploreAll_exploresEverythingAndCarriesNothing() {
        IncrementalPlan plan = IncrementalPlan.exploreAll();

        assertThat(plan.shouldExplore("post-api-orders")).isTrue();
        assertThat(plan.shouldExplore("anything")).isTrue();
        assertThat(plan.carriedPaths()).isEmpty();
    }
}
