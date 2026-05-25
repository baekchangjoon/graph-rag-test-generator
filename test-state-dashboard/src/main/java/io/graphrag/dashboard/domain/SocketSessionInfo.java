package io.graphrag.dashboard.domain;

import java.time.Instant;

/** TestRun이 보유한 socket mock 세션 추적 정보. */
public record SocketSessionInfo(
        String sessionId,
        String mockHost,
        int mockPort,
        Instant createdAt) {}
