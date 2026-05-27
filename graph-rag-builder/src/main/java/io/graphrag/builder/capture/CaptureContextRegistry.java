package io.graphrag.builder.capture;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Path-id keyed registry of {@link CaptureContext} instances, parallel to the
 * ThreadLocal {@link CaptureContext#current()} used by datasource-proxy path.
 *
 * <p>Use case — agent-bridge (Option A via jdbc-intercept-agent): the agent advice
 * fires on a Servlet handler thread that does <em>not</em> share the analysis thread's
 * ThreadLocal. The path-id reaches the advice through either
 * {@code JdbcCaptureSession.currentId()} (when the SUT is direct-invoked from the
 * analysis thread) or OTEL baggage (when the SUT is hit via HTTP and a Servlet handler
 * thread processes the request). The bridge then looks up the matching
 * {@link CaptureContext} from this registry.
 *
 * <p>Lifecycle: scout test calls {@link #register(String, CaptureContext)} on path
 * begin, drains via {@code ctx.capturedSql()} on path end, then
 * {@link #unregister(String)}. Concurrent paths (parallel test methods) get isolated
 * contexts by id.
 */
public final class CaptureContextRegistry {

    private static final ConcurrentMap<String, CaptureContext> BY_PATH_ID = new ConcurrentHashMap<>();

    private CaptureContextRegistry() {}

    public static void register(String pathId, CaptureContext ctx) {
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(ctx, "ctx");
        BY_PATH_ID.put(pathId, ctx);
    }

    public static CaptureContext forPathId(String pathId) {
        if (pathId == null) return null;
        return BY_PATH_ID.get(pathId);
    }

    /** Get or create a context for the given path id. Idempotent + thread-safe. */
    public static CaptureContext computeIfAbsent(String pathId) {
        Objects.requireNonNull(pathId, "pathId");
        return BY_PATH_ID.computeIfAbsent(pathId, CaptureContext::new);
    }

    /** Immutable snapshot of (pathId → context) entries — used by archive dump on shutdown. */
    public static Map<String, CaptureContext> snapshot() {
        return Map.copyOf(BY_PATH_ID);
    }

    /** Removes and returns the context (null if none). */
    public static CaptureContext unregister(String pathId) {
        if (pathId == null) return null;
        return BY_PATH_ID.remove(pathId);
    }

    /** Test-helper — clears all entries. */
    public static void clearAll() {
        BY_PATH_ID.clear();
    }

    public static int size() { return BY_PATH_ID.size(); }
}
