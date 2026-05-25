package io.graphrag.dashboard.domain;

import java.time.Instant;

/** TestRun이 등록한 HTTP mock stub 추적 정보. */
public record HttpStubInfo(
        String stubId,
        String urlPattern,
        String scopeBaggageValue,
        Instant createdAt) {}
