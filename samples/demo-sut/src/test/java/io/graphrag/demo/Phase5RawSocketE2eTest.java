package io.graphrag.demo;

import io.graphrag.agent.SocketByteRecorder;
import io.graphrag.demo.external.RawSocketPricingClient;
import io.graphrag.generator.compose.socket.SocketMockComposer;
import io.graphrag.model.CapturedSocketIO;
import io.graphrag.model.SocketDirection;
import io.graphrag.model.SocketFramework;
import io.graphrag.model.SocketProtocol;
import io.graphrag.socketmock.domain.Expectation;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import io.graphrag.socketmock.server.NettyServerManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 raw {@link Socket} E2E:
 * <ol>
 *   <li>mock TCP 서버 부팅
 *   <li>{@link RawSocketPricingClient}로 실제 호출이 가능함을 확인
 *   <li>같은 통신을 {@link SocketByteRecorder}로 wrap한 raw socket으로 재현 → byte 캡처
 *   <li>캡처된 byte로 {@link SocketMockComposer} 호출 → 합성 코드 검증
 * </ol>
 */
class Phase5RawSocketE2eTest {

    private static ExpectationRegistry registry;
    private static NettyServerManager mock;
    private static int port;

    @BeforeAll
    static void boot() throws Exception {
        registry = new ExpectationRegistry();
        mock = new NettyServerManager(registry);
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        mock.ensureBound(port);
    }

    @AfterAll
    static void shutdown() {
        if (mock != null) mock.shutdown();
    }

    @Test
    void rawSocketClientCommunicatesAndBytesCanBeCaptured() throws Exception {
        byte[] req = new byte[] {0x42, 0x42, 0x42, 0x42};
        byte[] resp = new byte[] {0x00, 0x00, 0x00, (byte) 0x99};
        registry.register(Expectation.builder()
                .port(port).sessionId("phase5-test")
                .onReceive(req).respond(resp)
                .build());

        // 1) 실 client로 통신이 되는지 확인
        RawSocketPricingClient client = new RawSocketPricingClient("127.0.0.1", port);
        assertThat(client.fetchPriceRaw(req)).isEqualTo(0x99);

        // 2) recorder로 wrap한 socket으로 같은 통신 → byte 캡처
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            SocketByteRecorder.Wrapped w = SocketByteRecorder.wrap(socket);

            OutputStream out = w.output;
            InputStream in = w.input;
            out.write(req);
            out.flush();
            byte[] buf = new byte[4];
            int r = 0;
            while (r < 4) {
                int n = in.read(buf, r, 4 - r);
                if (n < 0) break;
                r += n;
            }
            assertThat(r).isEqualTo(4);
            assertThat(w.writtenBuffer.toByteArray()).containsExactly(req);
            assertThat(w.readBuffer.toByteArray()).containsExactly(resp);
        }

        // 3) 캡처된 byte로 SocketMockComposer 합성 검증
        List<CapturedSocketIO> events = List.of(
                new CapturedSocketIO("io-1", "p-phase5",
                        SocketDirection.OUTBOUND, "127.0.0.1", port,
                        SocketByteRecorder.toHex(req), "agent-capture",
                        SocketProtocol.TCP, SocketFramework.RAW_SOCKET,
                        null, null),
                new CapturedSocketIO("io-2", "p-phase5",
                        SocketDirection.INBOUND, "127.0.0.1", port,
                        SocketByteRecorder.toHex(resp), "agent-capture",
                        SocketProtocol.TCP, SocketFramework.RAW_SOCKET,
                        null, null));

        String composed = SocketMockComposer.compose("127.0.0.1", port, events);
        assertThat(composed)
                .contains("socketMock.bind(\"127.0.0.1\", " + port + ")")
                .contains(".onReceiveHex(\"42 42 42 42\")")
                .contains(".respondHex(\"00 00 00 99\")")
                .contains(".register();");
    }
}
