package io.graphrag.generator.compose.socket;

import io.graphrag.model.CapturedSocketIO;
import io.graphrag.model.SocketDirection;
import io.graphrag.model.SocketFramework;
import io.graphrag.model.SocketProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SocketMockComposerTest {

    private CapturedSocketIO io(SocketDirection dir, String hex) {
        return new CapturedSocketIO(
                "s-1", "path-1", dir, "inv.host", 9000,
                hex, "msg", SocketProtocol.TCP, SocketFramework.NETTY, null, null);
    }

    @Test
    void composesBindAndOnReceiveRespond() {
        // 한 path에 OUTBOUND(요청) + INBOUND(응답) 쌍이 있다고 가정
        CapturedSocketIO outbound = io(SocketDirection.OUTBOUND, "01 02 03");
        CapturedSocketIO inbound = io(SocketDirection.INBOUND, "FF EE");

        String code = SocketMockComposer.compose("inv.host", 9000,
                List.of(outbound, inbound));

        assertThat(code).contains("socketMock.bind(\"inv.host\", 9000)");
        assertThat(code).contains(".onReceiveHex(\"01 02 03\")");
        assertThat(code).contains(".respondHex(\"FF EE\")");
        assertThat(code).contains(".register();");
    }

    @Test
    void includesSessionFieldWhenPresent() {
        CapturedSocketIO io = new CapturedSocketIO(
                "s-1", "path-1", SocketDirection.OUTBOUND,
                "inv.host", 9000, "01", "msg",
                SocketProtocol.TCP, SocketFramework.NETTY, null, "session-id");

        String code = SocketMockComposer.compose("inv.host", 9000,
                List.of(io));

        assertThat(code).contains(".withSessionField(\"session-id\"");
    }

    @Test
    void noInboundProducesEmptyResponse() {
        CapturedSocketIO outbound = io(SocketDirection.OUTBOUND, "01");
        String code = SocketMockComposer.compose("h", 80, List.of(outbound));

        assertThat(code).contains(".onReceiveHex(\"01\")");
        // 응답 없으면 respondHex 빈 문자열 또는 noop
        assertThat(code).contains(".respondHex(\"\")");
    }

    @Test
    void deterministicSameInput() {
        CapturedSocketIO io = io(SocketDirection.OUTBOUND, "AB CD");
        assertThat(SocketMockComposer.compose("h", 1, List.of(io)))
                .isEqualTo(SocketMockComposer.compose("h", 1, List.of(io)));
    }
}
