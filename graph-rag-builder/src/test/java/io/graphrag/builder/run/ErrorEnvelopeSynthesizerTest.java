package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorEnvelopeSynthesizerTest {

    @Test
    void synthesizesEnvelope_triggerFields_sentinel_detail() {
        var d = new ErrorContractDescriptor(List.of("errorCode"), "errorCode", "errorDetail", "BizException");
        JsonNode env = new ErrorEnvelopeSynthesizer().synthesize(d);
        assertThat(env.get("errorCode").asText()).isEqualTo("ERROR");      // trigger+status 센티넬(비어있지 않음)
        assertThat(env.get("errorDetail").asText()).isEqualTo("BizException");
    }

    @Test
    void deterministic() {
        var d = new ErrorContractDescriptor(List.of("errorCode"), "errorCode", "errorDetail", "BizException");
        var s = new ErrorEnvelopeSynthesizer();
        assertThat(s.synthesize(d).toString()).isEqualTo(s.synthesize(d).toString());
    }
}
