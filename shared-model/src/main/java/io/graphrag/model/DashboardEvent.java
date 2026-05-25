package io.graphrag.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 대시보드가 testlib로부터 수신하는 이벤트.
 *
 * <p>{@link #payload()}는 {@link #type()}별로 다른 record를 가진다. 소비자는 type을 보고
 * {@link com.fasterxml.jackson.databind.ObjectMapper#convertValue} 로 적절한 record로 변환한다.
 *
 * <p>대시보드 장애가 테스트에 영향 주지 않도록 fire-and-forget으로 발행됨 (testlib 측 책임).
 */
public record DashboardEvent(
        UUID eventId,
        DashboardEventType type,
        String testId,
        Instant timestamp,
        Object payload) {

    public DashboardEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
