package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMethodTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void containsAllStandardMethods() {
        assertThat(HttpMethod.values())
                .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD,
                        HttpMethod.OPTIONS);
    }

    @Test
    void jsonRoundTripPreservesAllValues() throws Exception {
        for (HttpMethod m : HttpMethod.values()) {
            String json = mapper.writeValueAsString(m);
            HttpMethod back = mapper.readValue(json, HttpMethod.class);
            assertThat(back).isEqualTo(m);
        }
    }

    @Test
    void jsonValueIsUppercaseString() throws Exception {
        String json = mapper.writeValueAsString(HttpMethod.POST);
        assertThat(json).isEqualTo("\"POST\"");
    }
}
