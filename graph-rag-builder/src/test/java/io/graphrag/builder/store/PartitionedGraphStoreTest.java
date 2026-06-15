package io.graphrag.builder.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.MapperStatement;
import io.graphrag.model.TableSchema;
import io.graphrag.model.WsEndpoint;
import io.graphrag.model.WsExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionedGraphStoreTest {

    @TempDir
    Path dir;

    private static GraphAsset twoPartitionAsset() {
        Endpoint orders = new Endpoint("post-api-orders", "POST", "/api/orders",
                "io.graphrag.sample.orders.OrderController", "create", List.of(), false);
        Endpoint users = new Endpoint("post-api-users", "POST", "/api/users",
                "io.graphrag.sample.users.UserController", "create", List.of(), false);
        JsonNode body = Json.mapper().createObjectNode().put("userId", "u1");
        ExploredPath orderPath = new ExploredPath("p-orders-1", "post-api-orders",
                body, 201, body, List.of("sql-1"), List.of("http-1"),
                List.of(), "heuristic", List.of(), List.of(), List.of());
        ExploredPath userPath = new ExploredPath("p-users-1", "post-api-users",
                body, 200, body, List.of("sql-2"), List.of(),
                List.of(), "heuristic", List.of(), List.of(), List.of());
        CapturedSql ordersSql = new CapturedSql("sql-1", "p-orders-1", "INSERT",
                "INSERT INTO orders", "orders", List.of());
        CapturedSql usersSql = new CapturedSql("sql-2", "p-users-1", "SELECT",
                "SELECT * FROM users", "users", List.of());
        CapturedHttpCall http = new CapturedHttpCall("http-1", "p-orders-1", "GET",
                "/inventory/stock", Map.of(), null, 200, "{}", List.of(), true);
        WsEndpoint wsEndpoint = new WsEndpoint("ws-orders-count", "/ws", "/app",
                "/app/orders/count", "/topic/orders",
                "io.graphrag.sample.orders.OrderCountWsController", "count", "CountRequest");
        WsExchange wsExchange = new WsExchange("wsx-1", "ws-orders-count",
                body, "/topic/orders", body, List.of("sql-3"));
        CapturedSql wsSql = new CapturedSql("sql-3", "wsx-1", "SELECT",
                "SELECT count(*) FROM orders", "orders", List.of());
        return new GraphAsset("order-service", "sha-1",
                List.of(orders, users),
                List.of(orderPath, userPath),
                List.of(ordersSql, usersSql, wsSql),
                List.of(new TableSchema("orders", List.of(), List.of(), List.of())),
                List.of(new MapperStatement("m-1", "io.graphrag.sample.orders.OrderMapper",
                        "search", "SELECT", true, "<select/>")),
                List.of(http),
                List.of(wsEndpoint),
                List.of(wsExchange),
                List.of(), List.of(),
                List.of());
    }

    @Test
    void save_shardsByHandlerPackage() {
        new PartitionedGraphStore(dir).save(twoPartitionAsset());

        assertThat(Files.exists(dir.resolve("global.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("partitions/io.graphrag.sample.orders.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("partitions/io.graphrag.sample.users.json"))).isTrue();
    }

    @Test
    void saveAndLoad_roundTripsAllFacts() {
        GraphAsset asset = twoPartitionAsset();
        PartitionedGraphStore store = new PartitionedGraphStore(dir);
        store.save(asset);

        GraphAsset loaded = store.load();

        assertThat(loaded.sutId()).isEqualTo(asset.sutId());
        assertThat(loaded.commitSha()).isEqualTo(asset.commitSha());
        assertThat(loaded.tables()).isEqualTo(asset.tables());
        assertThat(loaded.mappers()).isEqualTo(asset.mappers());
        assertThat(loaded.endpoints()).containsExactlyInAnyOrderElementsOf(asset.endpoints());
        assertThat(loaded.paths()).containsExactlyInAnyOrderElementsOf(asset.paths());
        assertThat(loaded.sql()).containsExactlyInAnyOrderElementsOf(asset.sql());
        assertThat(loaded.httpCalls()).containsExactlyInAnyOrderElementsOf(asset.httpCalls());
        assertThat(loaded.wsEndpoints()).containsExactlyInAnyOrderElementsOf(asset.wsEndpoints());
        assertThat(loaded.wsExchanges()).containsExactlyInAnyOrderElementsOf(asset.wsExchanges());
    }

    @Test
    void save_replacesStalePartitionsFromPreviousSave() {
        PartitionedGraphStore store = new PartitionedGraphStore(dir);
        store.save(twoPartitionAsset());

        GraphAsset ordersOnly = new GraphAsset("order-service", "sha-2",
                List.of(new Endpoint("post-api-orders", "POST", "/api/orders",
                        "io.graphrag.sample.orders.OrderController", "create",
                        List.of(), false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of());
        store.save(ordersOnly);

        GraphAsset loaded = store.load();
        assertThat(loaded.commitSha()).isEqualTo("sha-2");
        assertThat(loaded.endpoints()).extracting(Endpoint::id)
                .containsExactly("post-api-orders");
        assertThat(Files.exists(dir.resolve("partitions/io.graphrag.sample.users.json")))
                .isFalse();
    }
}
