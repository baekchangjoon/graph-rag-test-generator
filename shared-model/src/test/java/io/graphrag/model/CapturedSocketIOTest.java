package io.graphrag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedSocketIOTest {

    private final ObjectMapper mapper = JsonMappers.standard();

    @Test
    void outboundNettyTcpRoundTrip() throws Exception {
        CapturedSocketIO original = new CapturedSocketIO(
                "s-1", "path-1",
                SocketDirection.OUTBOUND,
                "inventory.host", 9000,
                "01 02 03 04",
                "serialized from ReserveStockRequest",
                SocketProtocol.TCP,
                SocketFramework.NETTY,
                null,
                null);

        String json = mapper.writeValueAsString(original);
        CapturedSocketIO back = mapper.readValue(json, CapturedSocketIO.class);

        assertThat(back).isEqualTo(original);
        assertThat(json).contains("\"direction\":\"OUTBOUND\"")
                .contains("\"protocol\":\"TCP\"")
                .contains("\"framework\":\"NETTY\"")
                .contains("\"byte_hex\":\"01 02 03 04\"")
                .contains("\"endpoint_port\":9000");
    }

    @Test
    void allEnumsPresent() {
        assertThat(SocketDirection.values())
                .containsExactly(SocketDirection.OUTBOUND, SocketDirection.INBOUND);
        assertThat(SocketProtocol.values())
                .containsExactly(SocketProtocol.TCP, SocketProtocol.UDP);
        assertThat(SocketFramework.values()).contains(
                SocketFramework.NETTY, SocketFramework.RAW_SOCKET, SocketFramework.OTHER);
    }
}
