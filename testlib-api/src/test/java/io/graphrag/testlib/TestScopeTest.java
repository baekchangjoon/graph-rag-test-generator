package io.graphrag.testlib;

import io.graphrag.model.DashboardEvent;
import io.graphrag.model.DashboardEventType;
import io.graphrag.model.ScopeCreatedPayload;
import io.graphrag.model.ScopeCleanedPayload;
import io.graphrag.testlib.api.DashboardReporter;
import io.graphrag.testlib.scope.TestScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestScopeTest {

    static class RecordingReporter implements DashboardReporter {
        final List<DashboardEvent> events = new ArrayList<>();
        @Override public void report(DashboardEvent event) { events.add(event); }
    }

    @Test
    void buildAssignsNonEmptyTestId() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("OrdersPostTest")
                .testMethod("createOrder")
                .runId("run-1")
                .dashboard(rep)
                .build();

        assertThat(scope.testId()).isNotBlank();
    }

    @Test
    void testIdIsUniqueAcrossInstances() {
        RecordingReporter rep = new RecordingReporter();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            TestScope s = TestScope.builder()
                    .testClass("T")
                    .testMethod("m")
                    .runId("r")
                    .dashboard(rep)
                    .build();
            seen.add(s.testId());
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void namespacedTestIdStartsWithPrefix() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("T")
                .testMethod("m")
                .runId("r")
                .dashboard(rep)
                .namespace("ordpost-create")
                .build();

        assertThat(scope.testId()).startsWith("ordpost-create-");
    }

    @Test
    void buildReportsScopeCreatedWithMetadata() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("OrdersPostTest")
                .testMethod("createOrder")
                .runId("run-42")
                .dashboard(rep)
                .build();

        assertThat(rep.events).hasSize(1);
        DashboardEvent ev = rep.events.get(0);
        assertThat(ev.type()).isEqualTo(DashboardEventType.SCOPE_CREATED);
        assertThat(ev.testId()).isEqualTo(scope.testId());

        ScopeCreatedPayload p = (ScopeCreatedPayload) ev.payload();
        assertThat(p.testClass()).isEqualTo("OrdersPostTest");
        assertThat(p.testMethod()).isEqualTo("createOrder");
        assertThat(p.runId()).isEqualTo("run-42");
    }

    @Test
    void cleanupReportsScopeCleaned() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("T")
                .testMethod("m")
                .runId("r")
                .dashboard(rep)
                .build();

        scope.cleanup();

        assertThat(rep.events).hasSize(2);
        DashboardEvent ev = rep.events.get(1);
        assertThat(ev.type()).isEqualTo(DashboardEventType.SCOPE_CLEANED);
        assertThat(ev.testId()).isEqualTo(scope.testId());
        assertThat(ev.payload()).isInstanceOf(ScopeCleanedPayload.class);
    }

    @Test
    void closeIsCleanupAlias() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("T")
                .testMethod("m")
                .runId("r")
                .dashboard(rep)
                .build();

        scope.close();

        assertThat(rep.events).hasSize(2);
        assertThat(rep.events.get(1).type()).isEqualTo(DashboardEventType.SCOPE_CLEANED);
    }

    @Test
    void cleanupIsIdempotent() {
        RecordingReporter rep = new RecordingReporter();
        TestScope scope = TestScope.builder()
                .testClass("T")
                .testMethod("m")
                .runId("r")
                .dashboard(rep)
                .build();

        scope.cleanup();
        scope.cleanup();   // 두 번 호출해도 추가 이벤트 없음

        long cleanedCount = rep.events.stream()
                .filter(e -> e.type() == DashboardEventType.SCOPE_CLEANED)
                .count();
        assertThat(cleanedCount).isEqualTo(1);
    }

    @Test
    void failureFastWhenRequiredMetadataMissing() {
        RecordingReporter rep = new RecordingReporter();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestScope.builder()
                .testMethod("m")
                .runId("r")
                .dashboard(rep)
                .build())
                .isInstanceOf(NullPointerException.class);
    }
}
