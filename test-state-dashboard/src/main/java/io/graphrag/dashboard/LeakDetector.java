package io.graphrag.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/** TTL 경과한 ACTIVE 스코프를 LEAKED로 표시 (docs/08). Reaper는 의도적으로 미구현. */
public class LeakDetector {

    private static final Logger log = LoggerFactory.getLogger(LeakDetector.class);

    private final TestRunStore store;
    private final Duration ttl;

    public LeakDetector(TestRunStore store, Duration ttl) {
        this.store = store;
        this.ttl = ttl;
    }

    public void scan(Instant now) {
        for (TestRun run : store.active()) {
            if (run.getStartedAt().plus(ttl).isBefore(now)) {
                run.markLeaked();
                log.warn("leak detected: testId={} startedAt={} ttl={}s",
                        run.getTestId(), run.getStartedAt(), ttl.toSeconds());
            }
        }
    }
}
