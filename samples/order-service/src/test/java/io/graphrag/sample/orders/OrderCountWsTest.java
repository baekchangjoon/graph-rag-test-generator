package io.graphrag.sample.orders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderCountWsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    OrderRepository orders;

    @BeforeEach
    void seed() {
        orders.deleteAll();
        users.deleteAll();
        User user = users.save(new User("u-ws", "Ws"));
        orders.save(new Order(user, 10, "NORMAL", "PENDING"));
        orders.save(new Order(user, 20, "NORMAL", "PENDING"));
    }

    @Test
    void countRequest_respondsOnTopicWithUserScopedCount() throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = stomp.connectAsync("ws://localhost:" + port + "/ws",
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);

        BlockingQueue<Map<String, Object>> received = new ArrayBlockingQueue<>(1);
        session.subscribe("/topic/orders", new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((Map<String, Object>) payload);
            }
        });

        session.send("/app/orders/count", Map.of("userId", "u-ws"));

        Map<String, Object> response = received.poll(10, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.get("userId")).isEqualTo("u-ws");
        assertThat(((Number) response.get("count")).intValue()).isEqualTo(2);
        session.disconnect();
    }
}
