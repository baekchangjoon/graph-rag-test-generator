package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** negative-auth path(무효 토큰 거부 arm 커버용)는 테스트 생성에서 제외된다. */
class GeneratorNegativeAuthTest {

    private static final Endpoint EP = new Endpoint("get-api-orders", "GET", "/api/orders",
            "x.OrderController", "list", List.of(), true);

    private static ExploredPath happy() {
        return new ExploredPath("get-api-orders-happy", "get-api-orders",
                Json.mapper().createObjectNode(), 200, Json.mapper().createArrayNode(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());
    }

    private static ExploredPath negativeAuth() {
        return new ExploredPath("get-api-orders-negauth", "get-api-orders",
                Json.mapper().createObjectNode(), 403, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "negative-auth", List.of(), List.of(), List.of());
    }

    private static GraphRagClient client(List<ExploredPath> paths) {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return EP; }
            public ExploredPath path(String id) {
                return paths.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            }
            public List<ExploredPath> pathsForEndpoint(String e) { return paths; }
            public List<CapturedSql> sqlForPath(String p) { return List.of(); }
            public List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String p) { return List.of(); }
            public boolean hasWsEndpoint(String id) { return false; }
            public io.graphrag.model.WsEndpoint wsEndpoint(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.WsExchange> wsExchangesFor(String w) { return List.of(); }
            public io.graphrag.model.WsExchange wsExchange(String id) { throw new UnsupportedOperationException(); }
            public boolean hasKafkaConsumer(String id) { return false; }
            public io.graphrag.model.KafkaConsumer kafkaConsumer(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.KafkaExchange> kafkaExchangesFor(String c) { return List.of(); }
            public List<io.graphrag.model.TableSchema> tables() { return List.of(); }
            public List<io.graphrag.model.RequiredSeed> seedsForPath(String p) { return List.of(); }
        };
    }

    @Test
    void skipsNegativeAuthPath_generatesOnlyHappy() {
        GenerationRequest req = new GenerationRequest(
                "get-api-orders", null, "OrdersListTest", "io.x", AuthMode.REAL);
        GenerationResult result = new Generator(client(List.of(happy(), negativeAuth()))).generate(req);

        // negative-auth path는 제외 → happy 1개만 생성
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).relativePath()).contains("OrdersListTest");
        assertThat(result.files()).noneSatisfy(f ->
                assertThat(f.relativePath()).containsIgnoringCase("negauth"));
    }
}
