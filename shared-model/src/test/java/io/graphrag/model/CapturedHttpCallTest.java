package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedHttpCallTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void httpClientTypeContainsAllValues() {
        assertThat(HttpClientType.values()).contains(
                HttpClientType.REST_TEMPLATE,
                HttpClientType.WEBCLIENT,
                HttpClientType.FEIGN,
                HttpClientType.OKHTTP,
                HttpClientType.OTHER);
    }

    @Test
    void httpClientTypeJsonRoundTrip() throws Exception {
        for (HttpClientType v : HttpClientType.values()) {
            String json = mapper.writeValueAsString(v);
            assertThat(mapper.readValue(json, HttpClientType.class)).isEqualTo(v);
        }
    }

    @Test
    void constructionAndJsonRoundTrip() throws Exception {
        CapturedHttpCall original = new CapturedHttpCall(
                "h-1", "path-1",
                "GET",
                "/inventory/stock?type={type}",
                "/inventory/stock?type=EXPRESS",
                List.of(new Binding(0, "EXPRESS", BindingOrigin.API_PARAM, "apiParam.type")),
                Map.of("Accept", "application/json"),
                null,
                List.of(),
                200,
                Map.of("available", 50),
                List.of("available"),
                HttpClientType.REST_TEMPLATE,
                "inventory-service");

        String json = mapper.writeValueAsString(original);
        CapturedHttpCall back = mapper.readValue(json, CapturedHttpCall.class);

        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.method()).isEqualTo("GET");
        assertThat(back.urlTemplate()).isEqualTo(original.urlTemplate());
        assertThat(back.responseStatus()).isEqualTo(200);
        assertThat(back.clientType()).isEqualTo(HttpClientType.REST_TEMPLATE);
        assertThat(back.responseFieldsReadBySut()).containsExactly("available");
        assertThat(json).contains("\"path_id\":\"path-1\"");
        assertThat(json).contains("\"url_template\":");
        assertThat(json).contains("\"url_concrete\":");
        assertThat(json).contains("\"response_status\":200");
        assertThat(json).contains("\"client_type\":\"REST_TEMPLATE\"");
        assertThat(json).contains("\"target_external_id\":\"inventory-service\"");
    }

    @Test
    void supportsPostWithJsonBody() throws Exception {
        CapturedHttpCall call = new CapturedHttpCall(
                "h-2", "path-1",
                "POST",
                "/inventory/reserve", "/inventory/reserve",
                List.of(),
                Map.of("Content-Type", "application/json"),
                Map.of("productId", "p-1", "qty", 5),
                List.of(
                        new Binding(0, "p-1", BindingOrigin.API_PARAM, "apiParam.productId"),
                        new Binding(1, 5, BindingOrigin.API_PARAM, "apiParam.qty")),
                201,
                Map.of("reservationId", "r-1"),
                List.of("reservationId"),
                HttpClientType.WEBCLIENT,
                "inventory-service");

        String json = mapper.writeValueAsString(call);
        CapturedHttpCall back = mapper.readValue(json, CapturedHttpCall.class);

        assertThat(back.requestBody()).isInstanceOfSatisfying(Map.class,
                m -> assertThat(m).containsEntry("productId", "p-1"));
        assertThat(back.requestBodyBindings()).hasSize(2);
    }
}
