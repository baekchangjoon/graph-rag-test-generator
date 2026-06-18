package sample.ledger;

import io.eventuate.tram.spring.consumer.jdbc.TramConsumerJdbcAutoConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// @EnableEventHandlers does NOT exist in eventuate-tram-spring-events:0.35.0 (verified by jar inspection).
// Subscription is wired manually via DomainEventDispatcherFactory in OrderEventHandlers.
@Configuration
@Import({TramConsumerJdbcAutoConfiguration.class, TramEventSubscriberConfiguration.class})
public class TramSubscriberConfig {}
