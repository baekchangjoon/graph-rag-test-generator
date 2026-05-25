package io.graphrag.demo.api;

import io.graphrag.demo.domain.OrderEntity;
import io.graphrag.demo.domain.OrderRepository;
import io.graphrag.demo.domain.UserRepository;
import io.graphrag.demo.external.InventoryClient;
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
    private final InventoryClient inventoryClient;

    public OrdersController(OrderRepository orderRepo, UserRepository userRepo,
                            InventoryClient inventoryClient) {
        this.orderRepo = orderRepo;
        this.userRepo = userRepo;
        this.inventoryClient = inventoryClient;
    }

    /** Phase 0/1: 외부 호출 없는 단순 endpoint. */
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

    /**
     * Phase 2: 외부 inventory 시스템 호출이 포함된 endpoint.
     * 흐름: 사용자 검증 → 외부 재고 조회 → 재고 충분하면 주문 등록.
     */
    @PostMapping("/with-inventory")
    public ResponseEntity<OrderResponse> createWithInventory(@Valid @RequestBody CreateOrderRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        if (userRepo.findById(req.userId()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int available = inventoryClient.availableForType(req.type());
        if (available < req.amount()) {
            return ResponseEntity.status(409).build();
        }
        OrderEntity saved = orderRepo.save(new OrderEntity(
                UUID.randomUUID().toString(),
                req.userId(),
                req.amount(),
                req.type(),
                "RESERVED"));
        return ResponseEntity.status(201).body(new OrderResponse(saved.getId(), saved.getStatus()));
    }
}
