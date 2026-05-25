package io.graphrag.dashboard.leak;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link LeakDetector#scan()}을 주기적으로 호출.
 */
@Component
public class LeakDetectorScheduler {

    private final LeakDetector detector;

    public LeakDetectorScheduler(LeakDetector detector) {
        this.detector = detector;
    }

    @Scheduled(fixedDelayString = "${dashboard.leak.scan-interval-ms:30000}")
    public void scanForLeaks() {
        detector.scan();
    }
}
