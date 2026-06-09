package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(String userId, Integer amount, String type) {
    }

    public record OrderResponse(Long id, String status) {
    }

    private final UserRepository users;
    private final OrderRepository orders;

    public OrderController(UserRepository users, OrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@RequestBody CreateOrderRequest request) {
        if (request.userId() == null || request.userId().isBlank()
                || request.amount() == null || request.amount() <= 0
                || request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid order request");
        }
        User user = users.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        Order saved = orders.save(new Order(user, request.amount(), request.type(), "PENDING"));
        return new OrderResponse(saved.getId(), saved.getStatus());
    }
}
