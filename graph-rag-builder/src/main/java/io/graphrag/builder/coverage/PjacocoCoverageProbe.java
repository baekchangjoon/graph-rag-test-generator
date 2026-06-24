package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionDataStore;

/**
 * pjacoco per-traceId .exec 기반 {@link CoverageProbe} 구현.
 *
 * <p>요청마다 고유한 traceId를 사용하므로 베이스라인 컷이 필요 없다.
 * 각 traceId 스토어는 생성 시 비어 있으므로 부팅/seed probe가 요청 delta에 섞이지 않는다.
 */
public final class PjacocoCoverageProbe implements CoverageProbe {

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
     * pjacoco {@code /__coverage__/test/stop}을 POST(flush)하고 {@code <traceId>.exec}를 폴링해 반환한다.
     *
     * @param traceId 이 요청의 W3C traceId (32-hex)
     */
    @Override
    public ExecutionDataStore requestDelta(String traceId) {
        backend.flush(traceId);
        return backend.awaitExec(traceId);
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
}
