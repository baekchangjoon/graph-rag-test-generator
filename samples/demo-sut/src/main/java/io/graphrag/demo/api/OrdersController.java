package io.graphrag.demo.api;

import io.graphrag.demo.domain.OrderEntity;
import io.graphrag.demo.domain.OrderRepository;
import io.graphrag.demo.domain.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrdersController {

    private final OrderRepository orderRepo;
    private final UserRepository userRepo;

    public OrdersController(OrderRepository orderRepo, UserRepository userRepo) {
        this.orderRepo = orderRepo;
        this.userRepo = userRepo;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (userRepo.findById(req.userId()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OrderEntity saved = orderRepo.save(new OrderEntity(
                UUID.randomUUID().toString(),
                req.userId(),
                req.amount(),
                req.type(),
                "PENDING"));
        return ResponseEntity.status(201).body(new OrderResponse(saved.getId(), saved.getStatus()));
    }
}
