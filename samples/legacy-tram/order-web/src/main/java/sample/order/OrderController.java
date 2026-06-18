package sample.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class OrderController {
    private final OrderRepository orders;
    private final RestTemplate http;
    private final String reservationUrl;

    public OrderController(OrderRepository orders, RestTemplate http,
                           @Value("${reservation.url:http://reservation:8080}") String reservationUrl) {
        this.orders = orders; this.http = http; this.reservationUrl = reservationUrl;
    }

    @PostMapping("/orders")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.get("userId"));
        int amount = ((Number) body.getOrDefault("amount", 0)).intValue();
        Order saved = orders.save(new Order(userId, amount));
        // 동기 HTTP → B (Sleuth가 B3 전파). 응답 시점엔 C(Tram) 미완 → 202.
        // Java 8: Map.of(Java9+) 미사용 — LinkedHashMap 사용.
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("orderId", saved.getId()); req.put("userId", userId); req.put("amount", amount);
        http.postForEntity(reservationUrl + "/reservations", req, Void.class);
        return ResponseEntity.accepted().body(
                Collections.singletonMap("orderId", saved.getId()));
    }
}
