package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.DashboardReporter;
import io.graphrag.testlib.scope.Config;

public interface DashboardAdapter {
    String name();
    DashboardReporter create(Config config);
}
