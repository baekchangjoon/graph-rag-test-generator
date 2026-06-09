package io.graphrag.dashboard;

import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunStoreTest {

    private final TestRunStore store = new TestRunStore();
    private static final Instant T0 = Instant.parse("2026-06-10T00:00:00Z");

    private TestEvent event(EventType type, String testId, String detailJson) throws Exception {
        return new TestEvent(type, testId, "run-1", T0,
                detailJson == null ? Json.mapper().nullNode() : Json.mapper().readTree(detailJson));
    }

    @Test
    void scopeCreated_registersActiveRun() throws Exception {
        store.apply(event(EventType.SCOPE_CREATED, "t-1", null));

        TestRun run = store.find("t-1");
        assertThat(run.getStatus()).isEqualTo(RunStatus.ACTIVE);
        assertThat(run.getStartedAt()).isEqualTo(T0);
        assertThat(store.active()).extracting(TestRun::getTestId).containsExactly("t-1");
    }

    @Test
    void dbRowEvents_trackResources() throws Exception {
        store.apply(event(EventType.SCOPE_CREATED, "t-1", null));
        store.apply(event(EventType.DB_ROW_INSERTED, "t-1",
                "{\"table\":\"users\",\"keyColumn\":\"id\",\"keyValue\":\"t-1-user\"}"));

        assertThat(store.find("t-1").getDbRows()).hasSize(1);
        assertThat(store.find("t-1").getDbRows().get(0).table()).isEqualTo("users");

        store.apply(event(EventType.DB_ROW_DELETED, "t-1",
                "{\"table\":\"users\",\"keyColumn\":\"id\",\"keyValue\":\"t-1-user\"}"));
        assertThat(store.find("t-1").getDbRows()).isEmpty();
    }

    @Test
    void scopeCleaned_marksRunCleaned() throws Exception {
        store.apply(event(EventType.SCOPE_CREATED, "t-1", null));
        store.apply(event(EventType.SCOPE_CLEANED, "t-1", null));

        assertThat(store.find("t-1").getStatus()).isEqualTo(RunStatus.CLEANED);
        assertThat(store.active()).isEmpty();
    }

    @Test
    void eventForUnknownScope_createsRunDefensively() throws Exception {
        // 이벤트 손실(fire-and-forget) 가능성 → SCOPE_CREATED 없이 와도 추적은 한다
        store.apply(event(EventType.DB_ROW_INSERTED, "t-x",
                "{\"table\":\"users\",\"keyColumn\":\"id\",\"keyValue\":\"v\"}"));
        assertThat(store.find("t-x")).isNotNull();
        assertThat(store.find("t-x").getDbRows()).hasSize(1);
    }
}
