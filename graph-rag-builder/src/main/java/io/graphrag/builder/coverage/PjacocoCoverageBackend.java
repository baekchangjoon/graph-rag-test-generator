package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * pjacoco per-trace 커버리지 백엔드.
 *
 * <p>OTel-scope 기반 per-request 커버리지 흐름 (PoC §5.1):
 * <ol>
 *   <li>호출자가 고유한 W3C traceId(공유 {@link io.graphrag.builder.capture.TraceParent}로 생성)를
 *       준비한다. 빌드 런 전체에서 유일하고 결정적이다.</li>
 *   <li>SUT 요청 헤더에 {@code traceparent: 00-&lt;traceId&gt;-...-01}를 실어 보낸다.
 *       pjacoco는 {@code traceKeyAutoCreate=true}로 OTel span 내 probe를 자동으로 traceId별로 귀속한다.</li>
 *   <li>응답 수신 후 {@link #flushAsync(String)}을 호출해 비동기로 stop 엔드포인트를 POST한다.</li>
 *   <li>{@link #awaitExec(String, long)}으로 &lt;traceId&gt;.exec 파일이 생길 때까지 폴링 후
 *       {@link ExecutionDataStore}를 반환한다. 타임아웃 시 경고 로그만 남기고 빈 store를 반환한다
 *       (절대 throw하지 않는다 — 커버리지 미수집이 빌드 실패가 돼선 안 된다).</li>
 * </ol>
 *
 * <h3>P2-4 동시성 안전성</h3>
 * <p>이 클래스는 {@code parallelism=N}인 다중 워커 스레드에서 동시에 {@link #flushAsync}/{@link #flush} /
 * {@link #awaitExec}을 호출해도 안전하다. 이유:
 * <ul>
 *   <li>모든 인스턴스 필드({@code host}, {@code controlPort}, {@code destfileDir}, {@code awaitTimeoutMs})는
 *       final — 초기화 이후 불변.</li>
 *   <li>{@link java.net.http.HttpClient}는 스레드 안전(JDK 명세).</li>
 *   <li>{@link java.util.concurrent.ExecutorService}(CachedThreadPool)는 스레드 안전.</li>
 *   <li>각 호출이 받는 {@code traceId}가 유일하므로 {@code <traceId>.exec} 파일이 충돌하지 않는다.</li>
 *   <li>per-call 로컬 변수만 사용 — 공유 mutable 버퍼·맵 없음.</li>
 * </ul>
 *
 * <h3>flush 풀 모델</h3>
 * <p>pjacoco 빌더는 <b>per-worker-synchronous</b> 모델을 사용한다: 각 워커가 자기 traceId로
 * flush → await → load를 직접 수행한다. 병렬화는 N개의 워커 스레드 자체에서 온다.
 * 따라서 {@code --flush-threads} CLI 플래그는 별도 flush 풀이 불필요한 이 모델에서는 미사용이며,
 * 향후 전략 변경 대비 플래그 수락만 한다. 실질적 타임아웃 튜닝 노브는 {@code --exec-await-ms}이다.
 *
 * <p>flush ExecutorService는 {@link #shutdown()}으로 드레인해야 한다.
 */
public final class PjacocoCoverageBackend {

    private static final Logger log = LoggerFactory.getLogger(PjacocoCoverageBackend.class);

    private static final long EXEC_POLL_INTERVAL_MS = 200;

    private final String host;
    private final int controlPort;
    private final Path destfileDir;
    private final long awaitTimeoutMs;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService flushExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pjacoco-flush");
        t.setDaemon(true);
        return t;
    });

    public PjacocoCoverageBackend(String host, int controlPort, Path destfileDir) {
        this(host, controlPort, destfileDir, 30_000L);
    }

    public PjacocoCoverageBackend(String host, int controlPort, Path destfileDir, long awaitTimeoutMs) {
        this.host = host;
        this.controlPort = controlPort;
        this.destfileDir = destfileDir;
        this.awaitTimeoutMs = awaitTimeoutMs;
    }

    // ── traceparent 생성 ──────────────────────────────────────────────────────

    /**
     * W3C traceparent 헤더 값을 만든다 ({@code 00-<traceId>-0000000000000001-01}).
     * traceId는 호출자가 {@link io.graphrag.builder.capture.TraceParent}로 생성한다.
     */
    public static String traceparentFor(String traceId) {
        return "00-" + traceId + "-0000000000000001-01";
    }

    // ── 제어 API ──────────────────────────────────────────────────────────────

    /**
     * pjacoco {@code /__coverage__/test/stop} 엔드포인트를 비동기로 POST한다.
     * 응답을 기다리지 않으므로 호출 즉시 반환된다.
     *
     * @return flush 작업의 Future (대기가 필요한 경우에만 사용)
     */
    public Future<?> flushAsync(String traceId) {
        String path = "/__coverage__/test/stop?testId=" + traceId + "&result=passed";
        return flushExecutor.submit(() -> post(path));
    }

    /**
     * pjacoco {@code /__coverage__/test/stop} 엔드포인트를 동기로 POST한다.
     */
    public void flush(String traceId) {
        post("/__coverage__/test/stop?testId=" + traceId + "&result=passed");
    }

    /**
     * POST stop with {@code format=binary}; returns exec bytes from the response body.
     * Falls back to {@link StopLoadPath#LEGACY_TEXT} when the agent responds with plain text.
     */
    public StopLoadOutcome stopAndLoad(String traceId, boolean persistToDisk) {
        String path = "/__coverage__/test/stop?testId=" + traceId
                + "&result=passed&format=binary&persist=" + persistToDisk;
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://" + host + ":" + controlPort + path))
                            .header("Accept", "application/octet-stream")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 204) {
                return new StopLoadOutcome(new ExecutionDataStore(), StopLoadPath.BINARY);
            }
            if (contentType.startsWith("application/octet-stream") && response.statusCode() == 200) {
                return new StopLoadOutcome(loadExecFromBytes(response.body()), StopLoadPath.BINARY);
            }
            if (contentType.startsWith("text/plain") && response.statusCode() == 200) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                if (body.startsWith("stopped ")) {
                    return new StopLoadOutcome(new ExecutionDataStore(), StopLoadPath.LEGACY_TEXT);
                }
            }
            log.warn("pjacoco binary stop failed for traceId={} — HTTP {} contentType={}",
                    traceId, response.statusCode(), contentType);
            return new StopLoadOutcome(new ExecutionDataStore(), StopLoadPath.ERROR);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("pjacoco binary stop request failed for traceId={}: {}", traceId, e.toString());
            return new StopLoadOutcome(new ExecutionDataStore(), StopLoadPath.ERROR);
        }
    }

    public enum StopLoadPath {
        BINARY,
        LEGACY_TEXT,
        ERROR
    }

    public record StopLoadOutcome(ExecutionDataStore store, StopLoadPath path) {
    }

    // ── exec 대기 + 로드 ──────────────────────────────────────────────────────

    /**
     * &lt;traceId&gt;.exec 파일이 생길 때까지 {@link #awaitTimeoutMs}ms 폴링 후 store를 반환한다.
     *
     * <p>타임아웃 시 경고 로그만 남기고 빈 {@link ExecutionDataStore}를 반환한다 (throw 없음).
     *
     * @param traceId      대기할 traceId
     * @param timeoutMs    이 호출에 적용할 타임아웃 (0이면 {@link #awaitTimeoutMs} 기본값 사용)
     */
    public ExecutionDataStore awaitExec(String traceId, long timeoutMs) {
        long limit = timeoutMs > 0 ? timeoutMs : awaitTimeoutMs;
        Path execFile = destfileDir.resolve(traceId + ".exec");
        Instant deadline = Instant.now().plusMillis(limit);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (Files.exists(execFile) && Files.size(execFile) > 0) {
                    return loadExec(execFile);
                }
            } catch (IOException ignored) {
                // retry
            }
            try {
                Thread.sleep(EXEC_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("pjacoco .exec not produced within {}ms for traceId={} at {} — returning empty store{}",
                limit, traceId, execFile, buildDiagnostics(traceId, execFile));
        return new ExecutionDataStore();
    }

    /**
     * {@link #awaitExec(String, long)} 기본 타임아웃 버전.
     */
    public ExecutionDataStore awaitExec(String traceId) {
        return awaitExec(traceId, 0);
    }

    /**
     * &lt;traceId&gt;.exec 파일을 동기로 로드한다 (파일이 이미 존재해야 함).
     */
    public ExecutionDataStore loadExec(String traceId) {
        return loadExec(destfileDir.resolve(traceId + ".exec"));
    }

    // ── 종료 ──────────────────────────────────────────────────────────────────

    /**
     * flush ExecutorService를 드레인하고 종료한다.
     * 최대 5초 대기 후 강제 종료한다.
     */
    public void shutdown() {
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── 진단 ──────────────────────────────────────────────────────────────────

    /**
     * 타임아웃 후 .exec 파일 상태를 검사해 원인을 좁히는 진단 문자열을 반환한다.
     */
    private String buildDiagnostics(String traceId, Path execFile) {
        StringBuilder sb = new StringBuilder();
        try {
            if (Files.exists(execFile)) {
                long size = Files.size(execFile);
                if (size == 0) {
                    sb.append("\n  [diagnosis] (F) .exec file exists but is empty (0 bytes)"
                            + " — no instrumented code executed on this trace, or I/O flush race");
                }
                // size > 0: loadExec must have failed — handled as a separate exception
            } else if (!Files.isDirectory(destfileDir)) {
                sb.append("\n  [diagnosis] (D) destfileDir does not exist: ").append(destfileDir)
                  .append(" — pjacoco agent -Ddestfile path may differ from builder config");
            } else {
                // destfileDir exists but no file for this traceId
                List<String> others = new ArrayList<>();
                try (var stream = Files.list(destfileDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".exec"))
                          .limit(5)
                          .forEach(p -> others.add(p.getFileName().toString()));
                } catch (IOException ignored) { }

                if (!others.isEmpty()) {
                    sb.append("\n  [diagnosis] (C) no .exec for traceId=").append(traceId)
                      .append(", but found other .exec files: ").append(others)
                      .append(" — traceparent header not forwarded, or traceKeyAutoCreate=false");
                } else {
                    sb.append("\n  [diagnosis] (B/E) destfileDir is empty"
                            + " — pjacoco agent may not be attached, or flush POST failed");
                }
            }
        } catch (IOException ignored) { }

        return sb.toString();
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────

    private void post(String path) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://" + host + ":" + controlPort + path))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "pjacoco control " + path + " -> HTTP " + response.statusCode()
                        + " body=" + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedIOException("pjacoco control request failed: " + path,
                    e instanceof IOException io ? io : new IOException(e.getMessage(), e));
        }
    }

    private static ExecutionDataStore loadExecFromBytes(byte[] execBytes) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(new ByteArrayInputStream(execBytes));
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("exec load failed from response body", e);
        }
    }

    private static ExecutionDataStore loadExec(Path execFile) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(execFile.toFile());
            return loader.getExecutionDataStore();
        } catch (IOException e) {
            throw new UncheckedIOException("exec load failed: " + execFile, e);
        }
    }
}
