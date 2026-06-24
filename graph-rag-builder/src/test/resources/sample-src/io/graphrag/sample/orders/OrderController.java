package io.graphrag.sample.orders;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /** static final String 상수 — extractor 소스트리 상수 해석 테스트용. */
    private static final String CONST_REGION = "X4";

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

            // 패턴 1: 기존 equals (리터럴.equals(accessor)) — "EMBARGOED"
            if ("EMBARGOED".equals(stock.region())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "region embargoed");
            }

            // 패턴 2: equalsIgnoreCase — "X1"
            if (stock.region().equalsIgnoreCase("X1")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "region X1 conflict");
            }

            // 패턴 3: Objects.equals (accessor, literal) — "X2"
            if (Objects.equals(stock.region(), "X2")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "region X2 conflict");
            }

            // 패턴 4: 로컬 변수 바인딩 후 equals — "X3"
            String r = stock.region();
            if ("X3".equals(r)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "region X3 conflict");
            }

            // 패턴 5: static final 상수 equals — "X4" (CONST_REGION)
            if (CONST_REGION.equals(stock.region())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "region X4 conflict");
            }

            // 패턴 6: startsWith — non-equality, loud skip 대상
            if (stock.region().startsWith("EMBARGO")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "region embargo prefix");
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
