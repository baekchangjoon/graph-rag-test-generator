package io.graphrag.demo;

import io.graphrag.demo.external.NettyPricingClient;
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

import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 Netty TCP E2E:
 * <ol>
 *   <li>socket-mock-server(Netty)에 expectation 등록
 *   <li>{@link NettyPricingClient}로 mock에 접속 → 요청/응답 byte 교환
 *   <li>observed 패턴을 {@link CapturedSocketIO}로 표현
 *   <li>{@link SocketMockComposer}로 testlib 등록 코드 생성 → 구조 검증
 * </ol>
 *
 * <p>운영 시 capture는 {@code io.graphrag.agent}의 ByteBuddy 트랜스포머가 자동 수집.
 * 본 테스트는 캡처 패턴이 SocketMockComposer까지 흘러 들어가는지 시연.
 */
class Phase4NettySocketE2eTest {

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
    void nettyClientExchangesBytesWithMockAndGetsRecognizableComposedSetup() {
        // 4 byte 요청 "I001" (49 30 30 31) → 4 byte 응답 1234 BE (00 00 04 D2)
        byte[] req = new byte[] {0x49, 0x30, 0x30, 0x31};
        byte[] resp = new byte[] {0x00, 0x00, 0x04, (byte) 0xD2};
        registry.register(Expectation.builder()
                .port(port).sessionId("phase4-test")
                .onReceive(req).respond(resp)
                .build());

        NettyPricingClient client = new NettyPricingClient("127.0.0.1", port);
        int price = client.fetchPrice(req);
        assertThat(price).isEqualTo(1234);

        // 캡처 결과를 CapturedSocketIO로 모델링
        List<CapturedSocketIO> events = List.of(
                new CapturedSocketIO("io-1", "p-phase4",
                        SocketDirection.OUTBOUND, "127.0.0.1", port,
                        "49 30 30 31", "agent-capture",
                        SocketProtocol.TCP, SocketFramework.NETTY,
                        null, null),
                new CapturedSocketIO("io-2", "p-phase4",
                        SocketDirection.INBOUND, "127.0.0.1", port,
                        "00 00 04 D2", "agent-capture",
                        SocketProtocol.TCP, SocketFramework.NETTY,
                        null, null));

        String composed = SocketMockComposer.compose("127.0.0.1", port, events);

        assertThat(composed)
                .contains("socketMock.bind(\"127.0.0.1\", " + port + ")")
                .contains(".onReceiveHex(\"49 30 30 31\")")
                .contains(".respondHex(\"00 00 04 D2\")")
                .contains(".register();");
    }
}
