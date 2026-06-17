package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import io.graphrag.model.KafkaConsumer;
import io.graphrag.model.WsEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndpointSelectorTest {

    private final Endpoint orders = new Endpoint("post-api-orders", "POST", "/api/orders",
            "io.x.OrderController", "create", List.of(), false);
    private final Endpoint getOrder = new Endpoint("get-api-orders-id", "GET", "/api/orders/{id}",
            "io.x.OrderController", "get", List.of(), false);

    @Test void matchesById() {
        var ids = EndpointSelector.resolve(List.of("post-api-orders"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(java.util.Set.of("post-api-orders"), ids);
    }

    @Test void matchesByMethodAndPath() {
        var ids = EndpointSelector.resolve(List.of("GET /api/orders/{id}"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(java.util.Set.of("get-api-orders-id"), ids);
    }

    @Test void multipleSpecs() {
        var ids = EndpointSelector.resolve(List.of("post-api-orders", "GET /api/orders/{id}"),
                List.of(orders, getOrder), List.of(), List.of());
        assertEquals(2, ids.size());
    }

    @Test void noMatchThrowsWithCandidates() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                EndpointSelector.resolve(List.of("POST /nope"),
                        List.of(orders), List.of(), List.of()));
        assertTrue(ex.getMessage().contains("POST /nope"));
        assertTrue(ex.getMessage().contains("post-api-orders"));   // candidate listed
    }

    @Test void resolvesWsAndKafkaById() {
        // WsEndpoint(id, wsPath, appPrefix, destination, sendTo, handlerClass, handlerMethod, payloadType)
        WsEndpoint ws = new WsEndpoint("ws-orders", "/ws", "/app", "/orders", "/topic/orders",
                "io.x.WsHandler", "handle", "io.x.OrderEvent");
        // KafkaConsumer(id, topic, groupId, handlerClass, handlerMethod, payloadType)
        KafkaConsumer kafka = new KafkaConsumer("kc-order-created", "order-created",
                "order-group", "io.x.KafkaHandler", "consume", "io.x.OrderEvent");

        var ids = EndpointSelector.resolve(List.of("ws-orders", "kc-order-created"),
                List.of(), List.of(ws), List.of(kafka));
        assertTrue(ids.contains("ws-orders"));
        assertTrue(ids.contains("kc-order-created"));
        assertEquals(2, ids.size());
    }
}
