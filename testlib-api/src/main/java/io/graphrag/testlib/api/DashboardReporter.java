package io.graphrag.testlib.api;

import io.graphrag.model.DashboardEvent;

/**
 * 대시보드 이벤트 발행 어댑터.
 *
 * <p>fire-and-forget. 발행 실패가 테스트를 실패시키지 않음.
 * {@code DASHBOARD_URL}이 설정되지 않으면 {@code noop} 어댑터가 선택됨.
 */
public interface DashboardReporter {

    /**
     * 이벤트 발행. 실패는 조용히 무시 (로그는 남길 수 있음).
     */
    void report(DashboardEvent event);
}
