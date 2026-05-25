package io.graphrag.dashboard.leak;

import io.graphrag.dashboard.domain.TestRunState;
import io.graphrag.dashboard.domain.TestRunStatus;
import io.graphrag.dashboard.store.TestRunRegistry;
import io.graphrag.model.DashboardEvent;
import io.graphrag.model.DashboardEventType;
import io.graphrag.model.ScopeCreatedPayload;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeakDetectorTest {

    static class RecordingAlerts implements AlertChannel {
        final List<TestRunState> alerts = new ArrayList<>();
        @Override public void onLeaked(TestRunState run) { alerts.add(run); }
    }

    @Test
    void marksActiveAsLeakedWhenTtlExceeded() {
        Instant t0 = Instant.parse("2026-05-25T10:00:00Z");
        TestRunRegistry reg = new TestRunRegistry();
        reg.handle(new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.SCOPE_CREATED,
                "stale", t0, new ScopeCreatedPayload("C", "m", "r")));

        Clock fixedFuture = Clock.fixed(t0.plusSeconds(400), ZoneOffset.UTC);
        RecordingAlerts alerts = new RecordingAlerts();
        LeakDetector det = new LeakDetector(reg, Duration.ofSeconds(300), fixedFuture, List.of(alerts));

        det.scan();

        assertThat(reg.get("stale").orElseThrow().status()).isEqualTo(TestRunStatus.LEAKED);
        assertThat(alerts.alerts).extracting(TestRunState::testId).containsExactly("stale");
    }

    @Test
    void leavesActiveAloneWhenWithinTtl() {
        Instant t0 = Instant.parse("2026-05-25T10:00:00Z");
        TestRunRegistry reg = new TestRunRegistry();
        reg.handle(new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.SCOPE_CREATED,
                "fresh", t0, new ScopeCreatedPayload("C", "m", "r")));

        Clock soon = Clock.fixed(t0.plusSeconds(100), ZoneOffset.UTC);
        RecordingAlerts alerts = new RecordingAlerts();
        LeakDetector det = new LeakDetector(reg, Duration.ofSeconds(300), soon, List.of(alerts));

        det.scan();

        assertThat(reg.get("fresh").orElseThrow().status()).isEqualTo(TestRunStatus.ACTIVE);
        assertThat(alerts.alerts).isEmpty();
    }

    @Test
    void doesNotReAlertAlreadyLeaked() {
        Instant t0 = Instant.parse("2026-05-25T10:00:00Z");
        TestRunRegistry reg = new TestRunRegistry();
        reg.handle(new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.SCOPE_CREATED,
                "x", t0, new ScopeCreatedPayload("C", "m", "r")));

        Clock far = Clock.fixed(t0.plusSeconds(500), ZoneOffset.UTC);
        RecordingAlerts alerts = new RecordingAlerts();
        LeakDetector det = new LeakDetector(reg, Duration.ofSeconds(300), far, List.of(alerts));

        det.scan();
        det.scan();

        assertThat(alerts.alerts).hasSize(1);
    }

    @Test
    void cleanedRunsAreNotMarkedLeaked() {
        Instant t0 = Instant.parse("2026-05-25T10:00:00Z");
        TestRunRegistry reg = new TestRunRegistry();
        reg.handle(new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.SCOPE_CREATED,
                "x", t0, new ScopeCreatedPayload("C", "m", "r")));
        reg.handle(new DashboardEvent(
                UUID.randomUUID(), DashboardEventType.SCOPE_CLEANED,
                "x", t0.plusSeconds(1),
                new io.graphrag.model.ScopeCleanedPayload(
                        new io.graphrag.model.ResourcesReleased(0, 0, 0))));

        Clock far = Clock.fixed(t0.plusSeconds(500), ZoneOffset.UTC);
        LeakDetector det = new LeakDetector(reg, Duration.ofSeconds(300), far, List.of());

        det.scan();

        assertThat(reg.get("x").orElseThrow().status()).isEqualTo(TestRunStatus.CLEANED);
    }
}
