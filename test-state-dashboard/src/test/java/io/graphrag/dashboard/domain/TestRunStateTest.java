package io.graphrag.dashboard.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunStateTest {

    @Test
    void newScopeStartsActiveWithNoResourcesAndNullEndedAt() {
        TestRunState s = TestRunState.start(
                "ord-a1b2c3",
                "OrdersPostTest",
                "createOrder",
                "run-1",
                Instant.parse("2026-05-25T10:00:00Z"));

        assertThat(s.testId()).isEqualTo("ord-a1b2c3");
        assertThat(s.testClass()).isEqualTo("OrdersPostTest");
        assertThat(s.status()).isEqualTo(TestRunStatus.ACTIVE);
        assertThat(s.endedAt()).isNull();
        assertThat(s.dbRows()).isEmpty();
        assertThat(s.httpStubs()).isEmpty();
        assertThat(s.socketSessions()).isEmpty();
    }

    @Test
    void addDbRowReturnsNewSnapshotWithRowAdded() {
        TestRunState s = TestRunState.start("t", "C", "m", "r", Instant.now())
                .withDbRowInserted(new DbRow("users", "id", "u-1", Instant.now()));

        assertThat(s.dbRows()).hasSize(1);
        assertThat(s.dbRows().get(0).keyValue()).isEqualTo("u-1");
    }

    @Test
    void removeDbRowFiltersOutMatching() {
        Instant now = Instant.now();
        TestRunState s = TestRunState.start("t", "C", "m", "r", now)
                .withDbRowInserted(new DbRow("users", "id", "u-1", now))
                .withDbRowInserted(new DbRow("orders", "id", "o-1", now))
                .withDbRowDeleted("users", "id", "u-1");

        assertThat(s.dbRows()).hasSize(1);
        assertThat(s.dbRows().get(0).table()).isEqualTo("orders");
    }

    @Test
    void cleanedMarksStatusAndEndedAt() {
        Instant start = Instant.parse("2026-05-25T10:00:00Z");
        Instant end = Instant.parse("2026-05-25T10:00:05Z");

        TestRunState s = TestRunState.start("t", "C", "m", "r", start)
                .withCleaned(end);

        assertThat(s.status()).isEqualTo(TestRunStatus.CLEANED);
        assertThat(s.endedAt()).isEqualTo(end);
    }

    @Test
    void leakedTransitionsActiveToLeaked() {
        TestRunState s = TestRunState.start("t", "C", "m", "r", Instant.now())
                .withLeaked();

        assertThat(s.status()).isEqualTo(TestRunStatus.LEAKED);
    }

    @Test
    void httpStubRegistrationAndRemoval() {
        Instant now = Instant.now();
        TestRunState s = TestRunState.start("t", "C", "m", "r", now)
                .withHttpStubRegistered(new HttpStubInfo("stub-1", "/inventory", "test-1", now))
                .withHttpStubRegistered(new HttpStubInfo("stub-2", "/credit", "test-1", now))
                .withHttpStubRemoved("stub-1");

        assertThat(s.httpStubs()).hasSize(1);
        assertThat(s.httpStubs().get(0).stubId()).isEqualTo("stub-2");
    }

    @Test
    void socketSessionRegistrationAndRemoval() {
        Instant now = Instant.now();
        TestRunState s = TestRunState.start("t", "C", "m", "r", now)
                .withSocketSessionOpened(new SocketSessionInfo("sess-1", "inv", 9000, now))
                .withSocketSessionClosed("sess-1");

        assertThat(s.socketSessions()).isEmpty();
    }
}
