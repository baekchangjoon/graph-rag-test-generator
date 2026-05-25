package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.DashboardReporter;
import io.graphrag.testlib.scope.Config;
import io.graphrag.testlib.spi.DashboardAdapter;

public final class NoopDashboardAdapter implements DashboardAdapter {
    @Override public String name() { return "noop"; }
    @Override public DashboardReporter create(Config config) { return new NoopDashboardReporter(); }
}
