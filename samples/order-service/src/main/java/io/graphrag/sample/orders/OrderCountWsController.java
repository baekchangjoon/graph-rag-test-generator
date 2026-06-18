package io.graphrag.sample.orders;

import java.util.List;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class OrderCountWsController {

    public record OrderCountRequest(String userId) {
    }

    public record OrderCountResponse(String userId, long count) {
    }

    public record OrderCountTotalResponse(long count) {
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

    /** Collection WS payload 회귀 가드: List&lt;String&gt; userIds 의 주문 수 합계. */
    @MessageMapping("/orders/count-batch")
    @SendTo("/topic/orders")
    public OrderCountTotalResponse countBatch(List<String> ids) {
        long total = 0;
        for (String id : ids) {
            total += orders.countByUser_Id(id);
        }
        return new OrderCountTotalResponse(total);
    }
}
