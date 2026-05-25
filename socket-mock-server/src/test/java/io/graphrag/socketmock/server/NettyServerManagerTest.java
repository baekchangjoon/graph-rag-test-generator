package io.graphrag.socketmock.server;

import io.graphrag.socketmock.domain.Expectation;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerManagerTest {

    private ExpectationRegistry registry;
    private NettyServerManager manager;
    private int ephemeralPort;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ExpectationRegistry();
        manager = new NettyServerManager(registry);
        try (ServerSocket ss = new ServerSocket(0)) {
            ephemeralPort = ss.getLocalPort();
        }
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void ensureBoundStartsListenerOnRequestedPort() {
        manager.ensureBound(ephemeralPort);
        assertThat(manager.isBound(ephemeralPort)).isTrue();
    }

    @Test
    void boundServerEchoesRespondBytesForMatchingInput() throws Exception {
        registry.register(Expectation.builder()
                .port(ephemeralPort).sessionId("itg")
                .onReceiveHex("01 02 03")
                .respondHex("FF EE")
                .build());
        manager.ensureBound(ephemeralPort);

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", ephemeralPort), 2000);
            client.setSoTimeout(3000);   // 응답 없으면 SocketTimeoutException
            OutputStream out = client.getOutputStream();
            out.write(new byte[] {0x01, 0x02, 0x03});
            out.flush();

            InputStream in = client.getInputStream();
            byte[] response = new byte[2];
            int read = in.read(response);
            assertThat(read).isEqualTo(2);
            assertThat(response).containsExactly((byte) 0xFF, (byte) 0xEE);
        }
    }

    @Test
    void ensureBoundIsIdempotent() {
        manager.ensureBound(ephemeralPort);
        manager.ensureBound(ephemeralPort);   // 두 번 호출해도 안전
        assertThat(manager.isBound(ephemeralPort)).isTrue();
    }
}
