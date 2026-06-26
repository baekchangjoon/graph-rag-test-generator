package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * pjacoco per-traceId .exec 기반 {@link CoverageProbe} 구현.
 *
 * <p>요청마다 고유한 traceId를 사용하므로 베이스라인 컷이 필요 없다.
 * 각 traceId 스토어는 생성 시 비어 있으므로 부팅/seed probe가 요청 delta에 섞이지 않는다.
 */
public final class PjacocoCoverageProbe implements CoverageProbe {

    private static final Logger log = LoggerFactory.getLogger(PjacocoCoverageProbe.class);
    private static final AtomicBoolean BINARY_MODE_LOGGED = new AtomicBoolean(false);

    private final PjacocoCoverageBackend backend;

    public PjacocoCoverageProbe(PjacocoCoverageBackend backend) {
        this.backend = backend;
    }

    /**
     * No-op. pjacoco는 traceId별 스토어를 독립적으로 관리하므로 전역 리셋이 불필요하다.
     * 각 traceId는 생성 시점에 비어 있으며 부팅/seed 구간 probe와 격리된다.
     */
    @Override
    public void baselineCut() {
        // no-op: pjacoco per-traceId 격리로 bootstrap/seed probe가 delta에 섞이지 않는다.
    }

    /**
     * pjacoco binary stop 응답 body에서 exec를 로드한다. 구 에이전트는 text stop 후 파일 폴링으로 폴백한다.
     *
     * @param traceId 이 요청의 W3C traceId (32-hex)
     */
    @Override
    public ExecutionDataStore requestDelta(String traceId) {
        PjacocoCoverageBackend.StopLoadOutcome outcome = backend.stopAndLoad(traceId, false);
        if (outcome.path() == PjacocoCoverageBackend.StopLoadPath.BINARY) {
            logBinaryModeOnce(true);
            return outcome.store();
        }
        if (outcome.path() == PjacocoCoverageBackend.StopLoadPath.LEGACY_TEXT) {
            logBinaryModeOnce(false);
            return backend.awaitExec(traceId);
        }
        return outcome.store();
    }

    /**
     * W3C traceparent 헤더 값을 반환한다 ({@code 00-<traceId>-0000000000000001-01}).
     */
    @Override
    public String traceparentFor(String traceId) {
        return PjacocoCoverageBackend.traceparentFor(traceId);
    }

    @Override
    public void shutdown() {
        backend.shutdown();
    }

    private static void logBinaryModeOnce(boolean enabled) {
        if (BINARY_MODE_LOGGED.compareAndSet(false, true)) {
            log.info("pjacoco binary stop: {}", enabled ? "enabled" : "fallback-to-file-poll");
        }
    }
}
