package io.graphrag.dashboard.leak;

import io.graphrag.dashboard.domain.TestRunState;
import io.graphrag.dashboard.domain.TestRunStatus;
import io.graphrag.dashboard.store.TestRunRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * TTL을 초과한 ACTIVE 테스트를 LEAKED로 마킹하고 등록된 알람 채널에 통보.
 *
 * <p>스케줄러는 외부 (Spring scheduled). 본 클래스는 단순히 {@link #scan()}을 노출.
 */
public class LeakDetector {

    private final TestRunRegistry registry;
    private final Duration ttl;
    private final Clock clock;
    private final List<AlertChannel> alertChannels;

    public LeakDetector(TestRunRegistry registry, Duration ttl, Clock clock,
                        List<AlertChannel> alertChannels) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertChannels = List.copyOf(Objects.requireNonNullElse(alertChannels, List.of()));
    }

    /** 단일 스캔. 호출자가 주기적으로 실행. */
    public void scan() {
        long nowEpochMs = clock.millis();
        for (TestRunState s : registry.listActive()) {
            long ageMs = nowEpochMs - s.startedAt().toEpochMilli();
            if (ageMs > ttl.toMillis()) {
                registry.markLeaked(s.testId()).ifPresent(leaked -> {
                    for (AlertChannel ch : alertChannels) {
                        try { ch.onLeaked(leaked); } catch (RuntimeException ignored) { /* 알람 실패는 무시 */ }
                    }
                });
            }
        }
    }
}
