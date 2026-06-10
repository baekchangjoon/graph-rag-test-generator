package io.graphrag.builder.index;

import io.graphrag.model.WsEndpoint;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WsEndpointIndexerTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void index_findsMessageMappingWithConfigPrefixes() {
        WsIndexResult result = new WsEndpointIndexer().index(SAMPLE_SRC);

        assertThat(result.endpoints()).hasSize(1);
        WsEndpoint endpoint = result.endpoints().get(0);
        assertThat(endpoint.id()).isEqualTo("ws-orders-count");
        assertThat(endpoint.wsPath()).isEqualTo("/ws");
        assertThat(endpoint.appPrefix()).isEqualTo("/app");
        assertThat(endpoint.destination()).isEqualTo("/orders/count");
        assertThat(endpoint.sendTo()).isEqualTo("/topic/orders");
        assertThat(endpoint.handlerClass())
                .isEqualTo("io.graphrag.sample.orders.OrderCountWsController");
        assertThat(endpoint.handlerMethod()).isEqualTo("count");
        assertThat(endpoint.payloadType())
                .isEqualTo("io.graphrag.sample.orders.OrderCountWsController$OrderCountRequest");
    }

    @Test
    void index_extractsPayloadShape() {
        WsIndexResult result = new WsEndpointIndexer().index(SAMPLE_SRC);
        BodyShape shape = result.payloadShapes()
                .get("io.graphrag.sample.orders.OrderCountWsController$OrderCountRequest");
        assertThat(shape.fields()).extracting(BodyShape.BodyField::name).containsExactly("userId");
    }
}
