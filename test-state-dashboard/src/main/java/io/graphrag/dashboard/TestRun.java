package io.graphrag.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** testId 1개의 자원 보유 상태 (docs/08 데이터 모델). */
public class TestRun {

    public record DbRow(String table, String keyColumn, String keyValue, Instant insertedAt) {
    }

    public record HttpStub(String stubId, String urlPattern, Instant createdAt) {
    }

    public record SocketSession(String sessionId, int mockPort, Instant createdAt) {
    }

    private final String testId;
    private final String runId;
    private final Instant startedAt;
    private volatile RunStatus status = RunStatus.ACTIVE;
    private volatile Instant cleanupAt;
    private final List<DbRow> dbRows = new CopyOnWriteArrayList<>();
    private final List<HttpStub> httpStubs = new CopyOnWriteArrayList<>();
    private final List<SocketSession> socketSessions = new CopyOnWriteArrayList<>();

    public TestRun(String testId, String runId, Instant startedAt) {
        this.testId = testId;
        this.runId = runId;
        this.startedAt = startedAt;
    }

    public String getTestId() {
        return testId;
    }

    public String getRunId() {
        return runId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getCleanupAt() {
        return cleanupAt;
    }

    public List<DbRow> getDbRows() {
        return dbRows;
    }

    public List<HttpStub> getHttpStubs() {
        return httpStubs;
    }

    public List<SocketSession> getSocketSessions() {
        return socketSessions;
    }

    void markCleaned(Instant at) {
        this.status = RunStatus.CLEANED;
        this.cleanupAt = at;
    }

    void markLeaked() {
        this.status = RunStatus.LEAKED;
    }
}
