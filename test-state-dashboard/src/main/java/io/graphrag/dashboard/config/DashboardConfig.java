package io.graphrag.dashboard.config;

import io.graphrag.dashboard.leak.AlertChannel;
import io.graphrag.dashboard.leak.LeakDetector;
import io.graphrag.dashboard.store.TestRunRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class DashboardConfig {

    @Bean
    public TestRunRegistry testRunRegistry() {
        return new TestRunRegistry();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public LeakDetector leakDetector(
            TestRunRegistry registry,
            Clock clock,
            List<AlertChannel> alertChannels,
            @Value("${dashboard.leak.ttl-seconds:300}") long ttlSeconds) {
        return new LeakDetector(registry, Duration.ofSeconds(ttlSeconds), clock, alertChannels);
    }
}
