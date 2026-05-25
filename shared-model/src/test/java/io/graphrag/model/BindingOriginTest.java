package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BindingOriginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAllExpectedValues() {
        assertThat(BindingOrigin.values())
                .containsExactly(
                        BindingOrigin.API_PARAM,
                        BindingOrigin.LITERAL,
                        BindingOrigin.COMPUTED,
                        BindingOrigin.CONFIG_PROPERTY,
                        BindingOrigin.GENERATED_BY_FRAMEWORK);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        for (BindingOrigin v : BindingOrigin.values()) {
            String json = mapper.writeValueAsString(v);
            BindingOrigin back = mapper.readValue(json, BindingOrigin.class);
            assertThat(back).isEqualTo(v);
        }
    }
}
