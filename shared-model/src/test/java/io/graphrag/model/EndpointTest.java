package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void constructsWithAllFields() {
        Endpoint e = new Endpoint(
                "POST:/api/orders",
                HttpMethod.POST,
                "/api/orders",
                "demo-sut",
                "io.graphrag.demo.OrdersController",
                "createOrder",
                false,
                List.of());

        assertThat(e.id()).isEqualTo("POST:/api/orders");
        assertThat(e.method()).isEqualTo(HttpMethod.POST);
        assertThat(e.authRequired()).isFalse();
        assertThat(e.requiredRoles()).isEmpty();
    }

    @Test
    void jsonRoundTripPreservesAllFields() throws Exception {
        Endpoint original = new Endpoint(
                "POST:/api/orders",
                HttpMethod.POST,
                "/api/orders",
                "demo-sut",
                "io.graphrag.demo.OrdersController",
                "createOrder",
                true,
                List.of("USER", "ADMIN"));

        String json = mapper.writeValueAsString(original);
        Endpoint back = mapper.readValue(json, Endpoint.class);

        assertThat(back).isEqualTo(original);
    }

    @Test
    void jsonUsesSnakeCaseFields() throws Exception {
        Endpoint e = new Endpoint(
                "GET:/users/{id}",
                HttpMethod.GET,
                "/users/{id}",
                "demo-sut",
                "Cls",
                "m",
                true,
                List.of("USER"));

        String json = mapper.writeValueAsString(e);

        assertThat(json).contains("\"handler_class\":\"Cls\"");
        assertThat(json).contains("\"handler_method\":\"m\"");
        assertThat(json).contains("\"auth_required\":true");
        assertThat(json).contains("\"required_roles\":[\"USER\"]");
    }

    @Test
    void recordEqualityByValue() {
        Endpoint a = new Endpoint("id1", HttpMethod.GET, "/p", "proj", "C", "m", false, List.of());
        Endpoint b = new Endpoint("id1", HttpMethod.GET, "/p", "proj", "C", "m", false, List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
