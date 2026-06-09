package io.graphrag.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class DashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DashboardApplication.class, args);
    }

    @Bean
    public LeakDetector leakDetector(TestRunStore store,
                                     @Value("${dashboard.ttl-seconds:300}") long ttlSeconds) {
        return new LeakDetector(store, Duration.ofSeconds(ttlSeconds));
    }
}
