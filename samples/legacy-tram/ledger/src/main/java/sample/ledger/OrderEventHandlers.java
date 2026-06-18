package sample.ledger;

import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sample.reservation.OrderReserved;

// NOTE: @EnableEventHandlers does NOT exist in eventuate-tram-spring-events:0.35.0 (verified by jar inspection).
// At 0.35.0 the subscriber is wired by: (1) TramEventSubscriberConfiguration provides DomainEventDispatcherFactory,
// (2) we create a DomainEventDispatcher bean via factory.make() + dispatcher.initialize().
@Component
public class OrderEventHandlers {
    private final LedgerEntryRepository ledger;
    public OrderEventHandlers(LedgerEntryRepository ledger) { this.ledger = ledger; }

    @Bean
    public DomainEventDispatcher domainEventDispatcher(DomainEventDispatcherFactory factory) {
        DomainEventDispatcher dispatcher = factory.make("ledgerServiceEvents", domainEventHandlers());
        dispatcher.initialize();
        return dispatcher;
    }

    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType("Order")
                .onEvent(OrderReserved.class, this::handle)
                .build();
    }

    @Transactional
    public void handle(DomainEventEnvelope<OrderReserved> env) {
        OrderReserved e = env.getEvent();
        ledger.save(new LedgerEntry(e.getOrderId(), e.getUserId(), e.getAmount()));  // 타깃 비동기 H5 SQL @C
    }
}
