package io.graphrag.demo.ws;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * Phase 3: STOMP 메시지 핸들러.
 *
 * <p>{@code /app/orders/notify} 로 전송된 메시지를 {@code /topic/orders} 로 broadcast.
 */
@Controller
public class OrderNotificationController {

    @MessageMapping("/orders/notify")
    @SendTo("/topic/orders")
    public OrderNotification notify(OrderNotification incoming) {
        return new OrderNotification(
                incoming.orderId(),
                "RECEIVED:" + incoming.message());
    }
}
