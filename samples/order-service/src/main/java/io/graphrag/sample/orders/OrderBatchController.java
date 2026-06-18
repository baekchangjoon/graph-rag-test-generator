package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Collection @RequestBody 회귀 가드: List&lt;DTO&gt; / List&lt;String&gt; 본문을 받는 엔드포인트.
 * 빌더의 BodyShape가 object-only일 때는 SKIP되므로, collection body 지원의 외부-루프 수용 대상이다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderBatchController {

    public record BatchResponse(int created) {
    }

    public record CountResponse(long count) {
    }

    private final UserRepository users;
    private final OrderRepository orders;

    public OrderBatchController(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse batch(@RequestBody List<OrderController.CreateOrderRequest> requests) {
        int created = 0;
        for (OrderController.CreateOrderRequest r : requests) {
            if (r.userId() == null || r.amount() == null || r.amount() <= 0 || r.type() == null) {
                continue;
            }
            User user = users.findById(r.userId()).orElse(null);
            if (user == null) {
                continue;
            }
            orders.save(new Order(user, r.amount(), r.type(), "PENDING"));
            created++;
        }
        return new BatchResponse(created);
    }

    @PostMapping("/by-ids")
    public CountResponse byIds(@RequestBody List<String> userIds) {
        long count = 0;
        for (String id : userIds) {
            count += orders.findByUser_Id(id).size();
        }
        return new CountResponse(count);
    }
}
