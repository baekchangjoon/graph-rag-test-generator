package io.graphrag.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 5 agent의 instrumentation 동작 시연용 counter.
 *
 * <p>실 InputStream/OutputStream 캡처는 후속 — 본 카운터는 ByteBuddy attach가 동작함을 입증.
 */
public final class CaptureCounter {

    private static final AtomicLong INVOCATIONS = new AtomicLong();

    private CaptureCounter() {}

    public static void incrementInvocations() {
        INVOCATIONS.incrementAndGet();
    }

    public static long invocations() {
        return INVOCATIONS.get();
    }

    public static void reset() {
        INVOCATIONS.set(0);
    }
}
