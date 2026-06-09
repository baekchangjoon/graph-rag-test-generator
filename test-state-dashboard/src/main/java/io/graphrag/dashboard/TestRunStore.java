package io.graphrag.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.TestEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** in-memory 상태. 이벤트 적용은 testId 단위로 직렬화된다. */
@Component
public class TestRunStore {

    /** 메모리 상한: 초과 시 종료 상태(CLEANED/LEAKED) run부터 제거. */
    static final int MAX_RUNS = 10_000;

    private final Map<String, TestRun> runs = new ConcurrentHashMap<>();

    public void apply(TestEvent event) {
        if (runs.size() >= MAX_RUNS && !runs.containsKey(event.testId())) {
            evictFinishedRuns();
            if (runs.size() >= MAX_RUNS) {
                return;   // 상한 도달 + 정리 불가 → 신규 이벤트 드랍 (모니터링 도구이므로 안전)
            }
        }
        TestRun run = runs.computeIfAbsent(event.testId(),
                id -> new TestRun(id, event.runId(), event.at()));
        synchronized (run) {
            switch (event.type()) {
                case SCOPE_CREATED -> {
                    // computeIfAbsent가 생성을 처리. 중복 수신은 무시.
                }
                case SCOPE_CLEANED -> run.markCleaned(event.at());
                case DB_ROW_INSERTED -> run.getDbRows().add(new TestRun.DbRow(
                        text(event.detail(), "table"),
                        text(event.detail(), "keyColumn"),
                        text(event.detail(), "keyValue"),
                        event.at()));
                case DB_ROW_DELETED -> run.getDbRows().removeIf(row ->
                        row.table().equals(text(event.detail(), "table"))
                                && row.keyValue().equals(text(event.detail(), "keyValue")));
                case HTTP_STUB_REGISTERED -> run.getHttpStubs().add(new TestRun.HttpStub(
                        text(event.detail(), "stubId"),
                        text(event.detail(), "urlPattern"),
                        event.at()));
                case HTTP_STUB_REMOVED -> run.getHttpStubs().removeIf(stub ->
                        stub.stubId().equals(text(event.detail(), "stubId")));
                case SOCKET_SESSION_OPENED -> run.getSocketSessions().add(new TestRun.SocketSession(
                        text(event.detail(), "sessionId"),
                        event.detail() != null && event.detail().has("mockPort")
                                ? event.detail().get("mockPort").asInt() : 0,
                        event.at()));
                case SOCKET_SESSION_CLOSED -> run.getSocketSessions().removeIf(session ->
                        session.sessionId().equals(text(event.detail(), "sessionId")));
                case AUTH_TOKEN_ISSUED -> {
                    // Phase 0: 추적 안 함
                }
            }
        }
    }

    private void evictFinishedRuns() {
        runs.values().removeIf(run ->
                run.getStatus() == RunStatus.CLEANED || run.getStatus() == RunStatus.LEAKED);
    }

    private static String text(JsonNode detail, String field) {
        return detail != null && detail.has(field) ? detail.get(field).asText() : "";
    }

    public TestRun find(String testId) {
        return runs.get(testId);
    }

    public List<TestRun> active() {
        return runs.values().stream().filter(r -> r.getStatus() == RunStatus.ACTIVE).toList();
    }

    public List<TestRun> leaked() {
        return runs.values().stream().filter(r -> r.getStatus() == RunStatus.LEAKED).toList();
    }
}
