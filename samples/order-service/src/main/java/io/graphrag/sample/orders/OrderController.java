package io.graphrag.sample.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record CreateOrderRequest(String userId, Integer amount, String type) {
    }

    public record OrderResponse(Long id, String status) {
    }

    public record OrderDetailResponse(Long id, String userId, Integer amount, String type, String status) {
    }

    private final UserRepository users;
    private final OrderRepository orders;
    private final InventoryClient inventory;
    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    public OrderController(UserRepository users, OrderRepository orders,
                           InventoryClient inventory,
                           org.springframework.beans.factory.ObjectProvider<org.springframework.kafka.core.KafkaTemplate<String, String>> kafkaTemplateProvider) {
        this.users = users;
        this.orders = orders;
        this.inventory = inventory;
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
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
            switch (stock.mode()) {
                case BACKORDER ->
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "backordered");
                case EXPRESS_ONLY -> {
                    // express-only 재고는 express 주문(이 블록은 항상 type=EXPRESS)에서만 허용된다.
                    // 단, 재고가 소진(available <= 0)이면 거절한다(STANDARD의 amount 비교와 구별되는 arm).
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
        if (kafkaTemplate != null) {
            try {
                String payload = String.format("{\"eventId\":\"%d\",\"type\":\"CREATED\",\"userId\":\"%s\"}",
                        saved.getId(), user.getId());
                kafkaTemplate.send("order.events", user.getId(), payload).get();
            } catch (Exception e) {
                // best-effort
            }
        }
        return new OrderResponse(saved.getId(), saved.getStatus());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDetailResponse> getById(@PathVariable Long id) {
        return orders.findById(id)
                .map(o -> ResponseEntity.ok(toDetail(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<OrderDetailResponse> getByUserId(@RequestParam String userId) {
        return orders.findByUser_Id(userId).stream().map(this::toDetail).toList();
    }

    private OrderDetailResponse toDetail(Order o) {
        return new OrderDetailResponse(o.getId(), o.getUser().getId(), o.getAmount(), o.getType(), o.getStatus());
    }
}
