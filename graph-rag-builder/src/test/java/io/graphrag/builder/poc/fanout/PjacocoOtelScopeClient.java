package io.graphrag.builder.poc.fanout;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reusable helper for the OTel-scope/traceId per-request coverage path.
 *
 * <p>Canonical per-request flow (§5 + §5.1 of the PoC design):
 * <ol>
 *   <li>Generate a unique W3C traceparent for each request: {@code 00-<32hex traceId>-<16hex>-01}.
 *       Use {@link #traceIdFor(int)} to produce deterministic, zero-collision IDs from a
 *       zero-based request index (no random — reproducible across runs).</li>
 *   <li>Send the HTTP request carrying {@code traceparent: <traceparent>} and let pjacoco
 *       (with {@code traceKeyAutoCreate=true}) create a per-traceId store automatically.</li>
 *   <li>After the response, call {@link #flush(String)} which POSTs to
 *       {@code /__coverage__/test/stop?testId=<traceId>&result=passed} to flush the store.</li>
 *   <li>Wait for the {@code <traceId>.exec} file, then call {@link #load(String)} to get an
 *       {@link ExecutionDataStore} ready for {@code CoverageFingerprint.of(store, appClasses)}.</li>
 * </ol>
 *
 * <p>This client is reused by V3(a), V2, V3b, and V4 PoC gates.
 *
 * <p>Why OTel-scope over baggage path: the OTel javaagent starts its servlet span before the
 * Spring Security filter chain, so {@code ThreadLocalContextStorage#attach} fires before
 * {@code JwtAuthenticationFilter} runs — meaning pre-servlet filter probes are captured
 * (commit {@code 71e4657}). The baggage path misses those 4 probes and sets
 * {@code incompleteAttribution=true}; this path does not.
 */
public final class PjacocoOtelScopeClient {

    private static final int EXEC_AWAIT_MS = 5_000;
    private final String host;
    private final int controlPort;
    private final Path destfileDir;
    private final HttpClient http = HttpClient.newHttpClient();

    public PjacocoOtelScopeClient(String host, int controlPort, Path destfileDir) {
        this.host = host;
        this.controlPort = controlPort;
        this.destfileDir = destfileDir;
    }

    // ── traceId / traceparent generation ────────────────────────────────────

    /**
     * Produces a deterministic 32-hex traceId from a zero-based request index.
     *
     * <p>Format: {@code 000000000000<N_padded_to_16hex>abcdef0123456789} — the high 16 hex
     * digits encode the index (no collision up to 2^63 requests), the low 16 are a fixed
     * discriminator to keep the ID non-zero in both halves.
     */
    public static String traceIdFor(int requestIndex) {
        // high 64 bits = zero-padded index; low 64 bits = fixed discriminator
        return String.format("%016x%016x", (long) requestIndex, 0xABCDEF0123456789L);
    }

    /**
     * Builds a W3C traceparent header value for the given traceId.
     * Uses span-id {@code 0000000000000001} and sampled flag {@code 01}.
     */
    public static String traceparentFor(String traceId) {
        return "00-" + traceId + "-0000000000000001-01";
    }

    // ── control API ─────────────────────────────────────────────────────────

    /**
     * Flushes the OTel-scope store for {@code traceId} by POSTing to
     * {@code /__coverage__/test/stop?testId=<traceId>&result=passed}.
     *
     * <p>No {@code /test/start} needed: {@code traceKeyAutoCreate=true} creates the store on
     * first probe attribution within the OTel span. Call this after the HTTP response returns.
     */
    public void flush(String traceId) {
        post("/__coverage__/test/stop?testId=" + traceId + "&result=passed");
    }

    // ── exec loading ─────────────────────────────────────────────────────────

    /**
     * Waits up to {@value EXEC_AWAIT_MS}ms for {@code <traceId>.exec} to appear, then loads it
     * into an {@link ExecutionDataStore}.
     *
     * @throws IllegalStateException if the file does not appear in time
     */
    public ExecutionDataStore awaitAndLoad(String traceId) throws InterruptedException {
        Path execFile = execPath(traceId);
        awaitExecFile(execFile, traceId);
        return load(execFile);
    }

    /**
     * Loads {@code <traceId>.exec} synchronously (no waiting). Use only when the file is
     * already known to exist (e.g. after {@link #awaitAndLoad} or an explicit poll).
     */
    public ExecutionDataStore load(String traceId) {
        return load(execPath(traceId));
    }

    /** Path to the {@code .exec} file for the given traceId in {@code destfileDir}. */
    public Path execPath(String traceId) {
        return destfileDir.resolve(traceId + ".exec");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void post(String path) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://" + host + ":" + controlPort + path))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("pjacoco control " + path + " -> HTTP " + r.statusCode()
                        + " body=" + r.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new UncheckedIOException("pjacoco control request failed: " + path,
                    e instanceof IOException io ? io : new IOException(e.getMessage(), e));
        }
    }

    private void awaitExecFile(Path execFile, String traceId) throws InterruptedException {
        Instant deadline = Instant.now().plusMillis(EXEC_AWAIT_MS);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Files.exists(execFile) && Files.size(execFile) > 0) return;
            } catch (IOException ignored) { /* retry */ }
            Thread.sleep(300);
        }
        throw new IllegalStateException(
                "pjacoco .exec not produced within " + EXEC_AWAIT_MS + "ms for traceId=" + traceId
                + " at " + execFile);
    }

    private static ExecutionDataStore load(Path execFile) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(execFile.toFile());
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("exec load failed: " + execFile, e);
        }
    }
}
