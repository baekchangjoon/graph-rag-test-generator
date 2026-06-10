package io.graphrag.sample.orders;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class OrderCountWsController {

    public record OrderCountRequest(String userId) {
    }

    public record OrderCountResponse(String userId, long count) {
    }

    private final OrderRepository orders;

    public OrderCountWsController(OrderRepository orders) {
        this.orders = orders;
    }

    @MessageMapping("/orders/count")
    @SendTo("/topic/orders")
    public OrderCountResponse count(OrderCountRequest request) {
        return new OrderCountResponse(request.userId(),
                orders.countByUser_Id(request.userId()));
    }
}
