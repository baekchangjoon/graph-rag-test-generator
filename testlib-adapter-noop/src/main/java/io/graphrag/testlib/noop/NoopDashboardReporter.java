package io.graphrag.testlib.noop;

import io.graphrag.model.DashboardEvent;
import io.graphrag.testlib.api.DashboardReporter;

/**
 * 이벤트를 조용히 폐기. {@code DASHBOARD_URL} 미설정 시 기본 어댑터.
 */
public final class NoopDashboardReporter implements DashboardReporter {
    @Override public void report(DashboardEvent event) { /* drop */ }
}
