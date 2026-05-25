package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedWsMessageTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void roundTrip() throws Exception {
        CapturedWsMessage original = new CapturedWsMessage(
                "w-1", "path-1",
                WsMessageDirection.OUTBOUND, WsEndpointStyle.STOMP,
                "/topic/orders/u-1",
                "{\"orderId\":\"o-1\",\"status\":\"PLACED\"}",
                "sess-A", List.of());

        String json = mapper.writeValueAsString(original);
        CapturedWsMessage back = mapper.readValue(json, CapturedWsMessage.class);

        assertThat(back).isEqualTo(original);
        assertThat(json).contains("\"direction\":\"OUTBOUND\"")
                .contains("\"style\":\"STOMP\"")
                .contains("\"session_id\":\"sess-A\"");
    }

    @Test
    void enumsAllPresent() {
        assertThat(WsMessageDirection.values())
                .containsExactly(WsMessageDirection.INBOUND, WsMessageDirection.OUTBOUND);
        assertThat(WsEndpointStyle.values()).contains(
                WsEndpointStyle.STOMP, WsEndpointStyle.RAW, WsEndpointStyle.JSR356);
    }
}
