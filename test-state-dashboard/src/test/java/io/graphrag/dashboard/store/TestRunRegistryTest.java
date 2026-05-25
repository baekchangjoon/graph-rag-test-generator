package io.graphrag.dashboard.store;

import io.graphrag.dashboard.domain.TestRunState;
import io.graphrag.dashboard.domain.TestRunStatus;
import io.graphrag.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunRegistryTest {

    private final TestRunRegistry registry = new TestRunRegistry();

    @Test
    void scopeCreatedRegistersActiveRun() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-1",
                new ScopeCreatedPayload("OrdersPostTest", "createOrder", "run-1")));

        TestRunState s = registry.get("t-1").orElseThrow();
        assertThat(s.status()).isEqualTo(TestRunStatus.ACTIVE);
        assertThat(s.testClass()).isEqualTo("OrdersPostTest");
    }

    @Test
    void scopeCleanedMarksStatusAndSetsEndedAt() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-2",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CLEANED, "t-2",
                new ScopeCleanedPayload(new ResourcesReleased(0, 0, 0))));

        TestRunState s = registry.get("t-2").orElseThrow();
        assertThat(s.status()).isEqualTo(TestRunStatus.CLEANED);
        assertThat(s.endedAt()).isNotNull();
    }

    @Test
    void dbRowInsertedAndDeletedTracked() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-3",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.DB_ROW_INSERTED, "t-3",
                new DbRowInsertedPayload("users", "id", "u-1")));
        registry.handle(event(DashboardEventType.DB_ROW_INSERTED, "t-3",
                new DbRowInsertedPayload("orders", "id", "o-1")));

        TestRunState s = registry.get("t-3").orElseThrow();
        assertThat(s.dbRows()).hasSize(2);

        registry.handle(event(DashboardEventType.DB_ROW_DELETED, "t-3",
                new DbRowDeletedPayload("users", "id", "u-1")));

        s = registry.get("t-3").orElseThrow();
        assertThat(s.dbRows()).hasSize(1);
        assertThat(s.dbRows().get(0).table()).isEqualTo("orders");
    }

    @Test
    void httpStubAndSocketSessionTracked() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-4",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.HTTP_STUB_REGISTERED, "t-4",
                new HttpStubRegisteredPayload("stub-1", "/inventory", "t-4")));
        registry.handle(event(DashboardEventType.SOCKET_SESSION_OPENED, "t-4",
                new SocketSessionOpenedPayload("sess-1", "inv", 9000)));

        TestRunState s = registry.get("t-4").orElseThrow();
        assertThat(s.httpStubs()).hasSize(1);
        assertThat(s.socketSessions()).hasSize(1);

        registry.handle(event(DashboardEventType.HTTP_STUB_REMOVED, "t-4",
                new HttpStubRemovedPayload("stub-1")));
        registry.handle(event(DashboardEventType.SOCKET_SESSION_CLOSED, "t-4",
                new SocketSessionClosedPayload("sess-1")));

        s = registry.get("t-4").orElseThrow();
        assertThat(s.httpStubs()).isEmpty();
        assertThat(s.socketSessions()).isEmpty();
    }

    @Test
    void activeListReflectsCurrentlyActiveRunsOnly() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "active-1",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "active-2",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "to-clean",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CLEANED, "to-clean",
                new ScopeCleanedPayload(new ResourcesReleased(0, 0, 0))));

        assertThat(registry.listActive()).extracting(TestRunState::testId)
                .containsExactlyInAnyOrder("active-1", "active-2");
    }

    @Test
    void leakedListReflectsLeakedOnly() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "active",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "leaked",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.markLeaked("leaked");

        assertThat(registry.listLeaked()).extracting(TestRunState::testId)
                .containsExactly("leaked");
        assertThat(registry.listActive()).extracting(TestRunState::testId)
                .containsExactly("active");
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(registry.get("unknown")).isEmpty();
    }

    @Test
    void tableHoldersReturnsRunsWithRowsInTable() {
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-a",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.SCOPE_CREATED, "t-b",
                new ScopeCreatedPayload("C", "m", "r")));
        registry.handle(event(DashboardEventType.DB_ROW_INSERTED, "t-a",
                new DbRowInsertedPayload("users", "id", "u-1")));
        registry.handle(event(DashboardEventType.DB_ROW_INSERTED, "t-b",
                new DbRowInsertedPayload("users", "id", "u-2")));
        registry.handle(event(DashboardEventType.DB_ROW_INSERTED, "t-b",
                new DbRowInsertedPayload("orders", "id", "o-1")));

        assertThat(registry.tableHolders("users"))
                .extracting(h -> h.keyValue() + "@" + h.ownerTestId())
                .containsExactlyInAnyOrder("u-1@t-a", "u-2@t-b");

        assertThat(registry.tableHolders("orders"))
                .extracting(TestRunRegistry.TableRowHolder::ownerTestId)
                .containsExactly("t-b");
    }

    private DashboardEvent event(DashboardEventType type, String testId, Object payload) {
        return new DashboardEvent(UUID.randomUUID(), type, testId, Instant.now(), payload);
    }
}
