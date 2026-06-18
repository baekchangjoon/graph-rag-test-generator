package sample.reservation;

import io.eventuate.tram.events.common.DomainEvent;

// NOTE: @EventType (io.eventuate.tram.events.common.EventType) does NOT exist in
// eventuate-tram-events:0.35.0.RELEASE. DefaultDomainEventNameMapping uses
// event.getClass().getName() as the event type header — so the type string is the FQCN
// "sample.reservation.OrderReserved". Service C (ledger) must use the same FQCN or
// provide a custom DomainEventNameMapping bean to match on the string "OrderReserved".
// This concern is tracked in the Task 4 report for follow-up in Task 5/6.
public class OrderReserved implements DomainEvent {
    private Long orderId;
    private String userId;
    private int amount;

    public OrderReserved() {}

    public OrderReserved(Long orderId, String userId, int amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public Long getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public int getAmount() { return amount; }
}
