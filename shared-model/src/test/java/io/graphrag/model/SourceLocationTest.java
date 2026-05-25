package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLocationTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void constructsAndExposesFields() {
        SourceLocation loc = new SourceLocation(
                "io.graphrag.demo.OrdersService", "placeOrder", 142);

        assertThat(loc.className()).isEqualTo("io.graphrag.demo.OrdersService");
        assertThat(loc.method()).isEqualTo("placeOrder");
        assertThat(loc.line()).isEqualTo(142);
    }

    @Test
    void jsonUsesClassFieldNameFromSchema() throws Exception {
        SourceLocation loc = new SourceLocation("Cls", "m", 10);

        String json = mapper.writeValueAsString(loc);

        assertThat(json).contains("\"class\":\"Cls\"");
        assertThat(json).contains("\"method\":\"m\"");
        assertThat(json).contains("\"line\":10");
    }

    @Test
    void jsonRoundTripPreservesFields() throws Exception {
        SourceLocation original = new SourceLocation("Some.Class", "method", 99);

        String json = mapper.writeValueAsString(original);
        SourceLocation back = mapper.readValue(json, SourceLocation.class);

        assertThat(back).isEqualTo(original);
    }
}
