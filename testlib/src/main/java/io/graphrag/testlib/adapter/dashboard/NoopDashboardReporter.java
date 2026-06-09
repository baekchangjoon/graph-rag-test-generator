package io.graphrag.testlib.adapter.dashboard;

import io.graphrag.model.TestEvent;
import io.graphrag.testlib.spi.DashboardReporter;

public final class NoopDashboardReporter implements DashboardReporter {

    @Override
    public void report(TestEvent event) {
    }
}
