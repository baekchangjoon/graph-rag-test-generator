package sample.reservation;   // ★ ledger 서비스지만 B와 FQCN 일치를 위해 sample.reservation 패키지로 선언

import io.eventuate.tram.events.common.DomainEvent;

// @EventType 는 0.35.0 에 없음(Task 4 검증). 라우팅 일치는 B와 동일 FQCN으로 달성(복제 유지).
public class OrderReserved implements DomainEvent {
    private Long orderId; private String userId; private int amount;
    public OrderReserved() {}
    public Long getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
