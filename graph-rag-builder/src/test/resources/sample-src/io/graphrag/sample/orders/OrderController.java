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
    private final InventoryClient inventory;

    public OrderController(UserRepository users, OrderRepository orders,
                           InventoryClient inventory) {
        this.users = users;
        this.orders = orders;
        this.inventory = inventory;
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
        if ("EXPRESS".equals(request.type())) {
            InventoryClient.InventoryResponse stock = inventory.check(request.type());
            if ("EMBARGOED".equals(stock.region())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");
            }
            switch (stock.mode()) {
                case BACKORDER ->
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "backordered");
                case EXPRESS_ONLY -> {
                    if (stock.available() <= 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "express only stock depleted");
                    }
                }
                case STANDARD -> {
                    if (stock.available() < request.amount()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient stock");
                    }
                }
            }
        }
        Order saved = orders.save(new Order(user, request.amount(), request.type(), "PENDING"));
        return new OrderResponse(saved.getId(), saved.getStatus());
    }
}
