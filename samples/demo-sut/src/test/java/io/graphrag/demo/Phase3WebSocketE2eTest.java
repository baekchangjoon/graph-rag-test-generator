package io.graphrag.demo;

import io.graphrag.demo.ws.OrderNotification;
import io.graphrag.model.CapturedWsMessage;
import io.graphrag.model.WsEndpointStyle;
import io.graphrag.model.WsMessageDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 WebSocket/STOMP E2E:
 * - demo-sut 부팅 (random port)
 * - STOMP 클라이언트로 /ws 접속, /topic/orders 구독
 * - /app/orders/notify에 메시지 전송 → /topic/orders로 broadcast 수신
 * - 송수신 메시지를 CapturedWsMessage로 표현 (캡처 패턴 시연)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Phase3WebSocketE2eTest {

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:ws;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
        r.add("external.inventory.url", () -> "http://localhost:1");
    }

    @LocalServerPort int port;

    @Test
    void stompSendAndReceiveCanBeCapturedAsWsMessages() throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());

        LinkedBlockingDeque<OrderNotification> received = new LinkedBlockingDeque<>();
        StompSession session = stomp.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/orders", new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return OrderNotification.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                received.add((OrderNotification) payload);
            }
        });
        Thread.sleep(200);  // subscribe 안정화

        OrderNotification sent = new OrderNotification("o-1", "hello");
        session.send("/app/orders/notify", sent);

        OrderNotification got = received.poll(5, TimeUnit.SECONDS);
        assertThat(got).isNotNull();
        assertThat(got.orderId()).isEqualTo("o-1");
        assertThat(got.message()).isEqualTo("RECEIVED:hello");

        // 캡처 패턴 시연: 보낸/받은 메시지를 CapturedWsMessage로 모델링
        CapturedWsMessage outbound = new CapturedWsMessage(
                "w-1", "path-1", WsMessageDirection.OUTBOUND, WsEndpointStyle.STOMP,
                "/app/orders/notify", sent, UUID.randomUUID().toString(), java.util.List.of());
        CapturedWsMessage inbound = new CapturedWsMessage(
                "w-2", "path-1", WsMessageDirection.INBOUND, WsEndpointStyle.STOMP,
                "/topic/orders", got, UUID.randomUUID().toString(), java.util.List.of());

        assertThat(outbound.direction()).isEqualTo(WsMessageDirection.OUTBOUND);
        assertThat(inbound.payload()).isEqualTo(got);

        session.disconnect();
    }
}
