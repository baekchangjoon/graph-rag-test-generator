package sample.reservation;

import io.eventuate.tram.events.publisher.DomainEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
public class ReservationController {
    private final ReservationRepository reservations;
    private final DomainEventPublisher eventPublisher;

    public ReservationController(ReservationRepository reservations, DomainEventPublisher eventPublisher) {
        this.reservations = reservations;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/reservations")
    @Transactional
    public ResponseEntity<Void> create(@RequestBody Map<String, Object> body) {
        Long orderId = ((Number) body.get("orderId")).longValue();
        String userId = String.valueOf(body.get("userId"));
        int amount = ((Number) body.getOrDefault("amount", 0)).intValue();
        reservations.save(new Reservation(orderId, userId, amount));     // H5 SQL @B
        // 같은 TX에서 outbox(message) insert — Eventuate 트랜잭셔널 아웃박스. trace 컨텍스트 보존.
        eventPublisher.publish("Order", String.valueOf(orderId),
                Collections.singletonList(new OrderReserved(orderId, userId, amount)));
        return ResponseEntity.accepted().build();
    }
}
