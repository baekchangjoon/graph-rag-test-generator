package sample.ledger;

import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.spring.consumer.jdbc.TramConsumerJdbcAutoConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// @EnableEventHandlers does NOT exist in eventuate-tram-spring-events:0.35.0 (verified by jar inspection).
// Subscription is wired via DomainEventDispatcherFactory — dispatcher bean lives here in the @Configuration
// (full CGLIB-proxy mode) rather than in the @Component (lite mode).
@Configuration
@Import({TramConsumerJdbcAutoConfiguration.class, TramEventSubscriberConfiguration.class})
public class TramSubscriberConfig {

    @Bean
    public DomainEventDispatcher domainEventDispatcher(OrderEventHandlers handlers,
                                                       DomainEventDispatcherFactory factory) {
        DomainEventDispatcher d = factory.make("ledgerServiceEvents", handlers.domainEventHandlers());
        d.initialize();
        return d;
    }
}
