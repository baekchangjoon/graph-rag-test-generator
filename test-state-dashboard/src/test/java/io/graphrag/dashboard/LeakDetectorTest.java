package io.graphrag.dashboard;

import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LeakDetectorTest {

    private static final Instant T0 = Instant.parse("2026-06-10T00:00:00Z");

    private final TestRunStore store = new TestRunStore();
    private final LeakDetector detector = new LeakDetector(store, Duration.ofSeconds(300));

    private void createScope(String testId) {
        store.apply(new TestEvent(EventType.SCOPE_CREATED, testId, "run-1", T0,
                Json.mapper().nullNode()));
    }

    @Test
    void activeRunPastTtl_becomesLeaked() {
        createScope("t-1");

        detector.scan(T0.plusSeconds(301));

        assertThat(store.find("t-1").getStatus()).isEqualTo(RunStatus.LEAKED);
        assertThat(store.leaked()).hasSize(1);
    }

    @Test
    void activeRunWithinTtl_staysActive() {
        createScope("t-1");

        detector.scan(T0.plusSeconds(299));

        assertThat(store.find("t-1").getStatus()).isEqualTo(RunStatus.ACTIVE);
        assertThat(store.leaked()).isEmpty();
    }

    @Test
    void cleanedRun_isNeverLeaked() {
        createScope("t-1");
        store.apply(new TestEvent(EventType.SCOPE_CLEANED, "t-1", "run-1",
                T0.plusSeconds(5), Json.mapper().nullNode()));

        detector.scan(T0.plusSeconds(10_000));

        assertThat(store.find("t-1").getStatus()).isEqualTo(RunStatus.CLEANED);
    }
}
