package io.graphrag.builder.cli;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EndpointSelectorGlobTest {

    // 실제 io.graphrag.model.Endpoint record 시그니처에 맞춤:
    // (id, httpMethod, path, handlerClass, handlerMethod, params, authRequired). 구현 시 소스로 최종 확인.
    private Endpoint ep(String id, String method, String path) {
        return new Endpoint(id, method, path, "C", "m", List.of(), false);
    }

    private final List<Endpoint> eps = List.of(
            ep("post-api-orders", "POST", "/api/orders"),
            ep("post-api-orders-batch", "POST", "/api/orders/batch"),
            ep("get-api-users-id", "GET", "/api/users/{id}"));

    @Test
    void globMatchesMethodPath() {
        Set<String> r = EndpointSelector.resolve(List.of("POST /api/orders/**"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders-batch"), r);  // /api/orders 자체는 /** 횡단 미포함
    }

    @Test
    void globMatchesId() {
        Set<String> r = EndpointSelector.resolve(List.of("post-api-orders-*"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders-batch"), r);
    }

    @Test
    void mixExactAndGlob() {
        Set<String> r = EndpointSelector.resolve(
                List.of("post-api-orders", "GET /api/users/**"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders", "get-api-users-id"), r);
    }

    @Test
    void exactBackwardCompat() {
        Set<String> r = EndpointSelector.resolve(List.of("POST /api/orders"), eps, List.of(), List.of());
        assertEquals(Set.of("post-api-orders"), r);
    }

    @Test
    void globZeroMatchFails() {
        assertThrows(IllegalArgumentException.class,
                () -> EndpointSelector.resolve(List.of("DELETE /nope/**"), eps, List.of(), List.of()));
    }
}
