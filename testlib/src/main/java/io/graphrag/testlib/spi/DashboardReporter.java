package io.graphrag.testlib.spi;

import io.graphrag.model.TestEvent;

/** fire-and-forget. 구현은 절대 예외를 던지지 않는다 (docs/08). */
public interface DashboardReporter {
    void report(TestEvent event);
}
