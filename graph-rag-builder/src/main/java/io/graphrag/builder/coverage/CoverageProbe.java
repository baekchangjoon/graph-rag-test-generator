package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionDataStore;

/**
 * 요청 단위 커버리지 수집 추상화.
 *
 * <p>유일 구현체 {@link PjacocoCoverageProbe} — pjacoco per-traceId {@code .exec} 방식.
 * (P1-6에서 JaCoCo TCP dump 백엔드를 전면 제거하고 pjacoco 단일 경로로 통합.) 인터페이스는
 * 향후 다른 커버리지 백엔드 도입 여지를 위해 유지한다.
 */
public interface CoverageProbe {

    /**
     * 베이스라인 컷: 직전 구간(부팅/seed)의 probe를 버리고 다음 요청의 delta 측정을 시작한다.
     *
     * <p>JaCoCo 구현: {@code coverage.dump(true)}(결과 버림)로 전역 카운터를 리셋한다.<br>
     * pjacoco 구현: no-op. 각 traceId 스토어는 생성 시 비어 있으므로 부팅/seed probe가
     * 요청 delta에 섞이지 않는다.
     */
    void baselineCut();

    /**
     * 요청 1건의 커버리지 delta를 반환한다.
     *
     * <p>JaCoCo 구현: {@code coverage.dump(true)} — 리셋 동반 전역 덤프.<br>
     * pjacoco 구현: {@code stopAndLoad(traceId)} — binary stop 응답 body에서 exec 로드;
     * 구 에이전트는 {@code awaitExec(traceId)} 파일 폴링으로 폴백.
     *
     * @param traceId 이 요청에 할당된 W3C traceId (32-hex). JaCoCo 구현에서는 무시된다.
     * @return 이 요청에서 실행된 probe만 담은 {@link ExecutionDataStore}
     */
    ExecutionDataStore requestDelta(String traceId);

    /**
     * traceId에 대응하는 W3C {@code traceparent} 헤더 값을 반환한다.
     *
     * <p>JaCoCo 구현: {@code null} (traceparent 헤더 불필요 — 기존 baggage만 사용).<br>
     * pjacoco 구현: {@code "00-<traceId>-0000000000000001-01"}.
     *
     * @param traceId 이 요청에 할당된 traceId
     * @return traceparent 헤더 값, 또는 null(불필요 시)
     */
    default String traceparentFor(String traceId) {
        return null;
    }

    /**
     * traceparent를 실제로 주입하지 않은 요청(예: WS 교환)에서 커버리지 delta를 요청할 때 사용한다.
     * pjacoco 백엔드에서는 30초 폴링 없이 즉시 빈 {@link org.jacoco.core.data.ExecutionDataStore}를 반환한다.
     *
     * <p>traceparent를 주입하지 않은 요청에 {@link #requestDelta(String)}를 호출하면
     * pjacoco는 해당 traceId의 .exec 파일을 절대 기록하지 않으므로 타임아웃이 발생한다.
     * 이 메서드를 대신 사용하면 불필요한 30초 대기를 제거할 수 있다.
     */
    default org.jacoco.core.data.ExecutionDataStore noCoverageDelta() {
        return new org.jacoco.core.data.ExecutionDataStore();
    }

    /**
     * 리소스 정리 (shutdown 필요 시 구현). 기본 no-op.
     */
    default void shutdown() {
        // no-op
    }
}
