package io.graphrag.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointTest {
    @Test
    void targetUri_present_via8ArgCtor() {
        Endpoint gw = new Endpoint("g", "GET", "/api/v1/**", "C", "m", List.of(), false, "http://downstream");
        assertThat(gw.targetUri()).isEqualTo("http://downstream");
    }
    @Test
    void targetUri_nullByDefault_via7ArgCompatCtor() {
        Endpoint plain = new Endpoint("p", "GET", "/x", "C", "m", List.of(), false);
        assertThat(plain.targetUri()).isNull();
    }

    @Test
    void errorMessageLiterals_emptyByDefault_viaCompatCtors() {
        // REQ-D 후방호환: 7-arg/8-arg 생성자는 빈 목록으로 정규화한다.
        Endpoint plain = new Endpoint("p", "GET", "/x", "C", "m", List.of(), false);
        Endpoint gw = new Endpoint("g", "GET", "/y", "C", "m", List.of(), false, "http://d");
        assertThat(plain.errorMessageLiterals()).isEmpty();
        assertThat(gw.errorMessageLiterals()).isEmpty();
    }

    @Test
    void errorMessageLiterals_legacyJsonWithoutField_deserializesToEmptyList() throws Exception {
        // REQ-D 후방호환: 구버전 graph.json(필드 부재) 역직렬화 시 null이 아니라 빈 목록.
        String legacy = "{\"id\":\"p\",\"httpMethod\":\"GET\",\"path\":\"/x\",\"handlerClass\":\"C\","
                + "\"handlerMethod\":\"m\",\"params\":[],\"authRequired\":false}";
        Endpoint e = Json.mapper().readValue(legacy, Endpoint.class);
        assertThat(e.errorMessageLiterals()).isNotNull().isEmpty();
    }

    @Test
    void errorMessageLiterals_roundTrip() throws Exception {
        Endpoint e = new Endpoint("p", "POST", "/x", "C", "m", List.of(), false, null,
                List.of("nights must be between 1 and 30"));
        String json = Json.mapper().writeValueAsString(e);
        Endpoint back = Json.mapper().readValue(json, Endpoint.class);
        assertThat(back.errorMessageLiterals()).containsExactly("nights must be between 1 and 30");
    }
}
