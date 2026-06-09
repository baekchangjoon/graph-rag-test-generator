package io.graphrag.testlib.adapter.dashboard;

import io.graphrag.testlib.spi.DashboardReporter;
import io.graphrag.testlib.spi.Env;

/** DASHBOARD_URL 설정 시 http, 미설정 시 noop (docs/08 실행 모드). */
public final class DashboardReporters {

    private DashboardReporters() {
    }

    public static DashboardReporter fromEnv(Env env) {
        String url = env.get("DASHBOARD_URL");
        if (url == null || url.isBlank()) {
            return new NoopDashboardReporter();
        }
        return new HttpDashboardReporter(url);
    }
}
