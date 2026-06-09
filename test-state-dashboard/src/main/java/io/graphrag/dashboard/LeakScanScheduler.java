package io.graphrag.dashboard;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class LeakScanScheduler {

    private final LeakDetector detector;

    public LeakScanScheduler(LeakDetector detector) {
        this.detector = detector;
    }

    @Scheduled(fixedDelayString = "${dashboard.scan-interval-ms:30000}")
    public void scan() {
        detector.scan(Instant.now());
    }
}
