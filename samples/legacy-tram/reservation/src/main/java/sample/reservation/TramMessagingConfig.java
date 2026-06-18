package sample.reservation;

import io.eventuate.tram.spring.events.publisher.TramEventsPublisherConfiguration;
import io.eventuate.tram.spring.messaging.producer.jdbc.TramMessageProducerJdbcConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({TramMessageProducerJdbcConfiguration.class, TramEventsPublisherConfiguration.class})
public class TramMessagingConfig {
    // eventuate-tram-spring-cloud-sleuth-tram-starter(Task 2 확정 좌표)가 클래스패스에 있으면 자동 구성으로 B3가 메시지에 전파(1순위).
}
