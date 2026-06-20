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
}
