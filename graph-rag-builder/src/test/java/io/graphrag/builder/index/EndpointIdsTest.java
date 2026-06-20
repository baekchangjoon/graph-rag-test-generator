package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointIdsTest {
    @Test
    void of_matchesLegacyEndpointIdScheme() {
        // Must equal the id that EndpointIndexer used to produce (zero regression).
        assertThat(EndpointIds.of("POST", "/api/orders")).isEqualTo("post-api-orders");
        assertThat(EndpointIds.of("GET", "/api/orders/{id}")).isEqualTo("get-api-orders-id");
    }
}
