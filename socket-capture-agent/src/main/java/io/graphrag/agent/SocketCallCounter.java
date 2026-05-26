package io.graphrag.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SocketSystemInstaller}의 advice가 누적시키는 호출 카운터.
 *
 * <p>{@link CaptureCounter}와 분리 — sample target과 socket을 별도로 추적.
 */
public final class SocketCallCounter {

    private static final AtomicLong STREAM_REQUESTS = new AtomicLong();

    private SocketCallCounter() {}

    public static void incrementStreamRequests() {
        STREAM_REQUESTS.incrementAndGet();
    }

    public static long streamRequests() {
        return STREAM_REQUESTS.get();
    }

    public static void reset() {
        STREAM_REQUESTS.set(0);
    }
}
