package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BindingTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void constructsApiParamBinding() {
        Binding b = new Binding(1, "user-42", BindingOrigin.API_PARAM, "apiParam.userId");

        assertThat(b.position()).isEqualTo(1);
        assertThat(b.value()).isEqualTo("user-42");
        assertThat(b.origin()).isEqualTo(BindingOrigin.API_PARAM);
        assertThat(b.originRef()).isEqualTo("apiParam.userId");
    }

    @Test
    void constructsLiteralBinding() {
        Binding b = new Binding(2, "PENDING", BindingOrigin.LITERAL, null);

        assertThat(b.origin()).isEqualTo(BindingOrigin.LITERAL);
        assertThat(b.originRef()).isNull();
    }

    @Test
    void jsonRoundTripWithStringValue() throws Exception {
        Binding original = new Binding(0, "EXPRESS", BindingOrigin.API_PARAM, "apiParam.type");

        String json = mapper.writeValueAsString(original);
        Binding back = mapper.readValue(json, Binding.class);

        assertThat(back).isEqualTo(original);
        assertThat(json).contains("\"origin\":\"API_PARAM\"");
        assertThat(json).contains("\"origin_ref\":\"apiParam.type\"");
    }

    @Test
    void jsonRoundTripWithNumericValue() throws Exception {
        Binding original = new Binding(1, 100, BindingOrigin.API_PARAM, "apiParam.amount");

        String json = mapper.writeValueAsString(original);
        Binding back = mapper.readValue(json, Binding.class);

        assertThat(back.position()).isEqualTo(original.position());
        assertThat(back.origin()).isEqualTo(original.origin());
        // Jackson may deserialize JSON number as Integer; equality에서 value 비교는 별도 처리
        assertThat(((Number) back.value()).intValue()).isEqualTo(100);
    }

    @Test
    void jsonRoundTripWithNullOriginRef() throws Exception {
        Binding original = new Binding(3, "ACTIVE", BindingOrigin.LITERAL, null);

        String json = mapper.writeValueAsString(original);
        Binding back = mapper.readValue(json, Binding.class);

        assertThat(back).isEqualTo(original);
        assertThat(back.originRef()).isNull();
    }
}
