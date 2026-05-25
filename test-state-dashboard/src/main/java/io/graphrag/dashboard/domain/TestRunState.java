package io.graphrag.dashboard.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 한 테스트 실행의 상태와 보유 자원. Immutable record.
 *
 * <p>각 mutator (with*)는 새 인스턴스를 반환한다. Registry가 atomic하게 교체.
 */
public record TestRunState(
        String testId,
        String testClass,
        String testMethod,
        String runId,
        Instant startedAt,
        Instant endedAt,
        TestRunStatus status,
        List<DbRow> dbRows,
        List<HttpStubInfo> httpStubs,
        List<SocketSessionInfo> socketSessions) {

    public TestRunState {
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(testClass, "testClass");
        Objects.requireNonNull(testMethod, "testMethod");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(status, "status");
        dbRows = List.copyOf(Objects.requireNonNullElse(dbRows, List.of()));
        httpStubs = List.copyOf(Objects.requireNonNullElse(httpStubs, List.of()));
        socketSessions = List.copyOf(Objects.requireNonNullElse(socketSessions, List.of()));
    }

    public static TestRunState start(String testId, String testClass, String testMethod,
                                     String runId, Instant startedAt) {
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, null, TestRunStatus.ACTIVE,
                List.of(), List.of(), List.of());
    }

    public TestRunState withCleaned(Instant endedAt) {
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, TestRunStatus.CLEANED,
                dbRows, httpStubs, socketSessions);
    }

    public TestRunState withLeaked() {
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, TestRunStatus.LEAKED,
                dbRows, httpStubs, socketSessions);
    }

    public TestRunState withDbRowInserted(DbRow row) {
        List<DbRow> next = new ArrayList<>(dbRows);
        next.add(row);
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, next, httpStubs, socketSessions);
    }

    public TestRunState withDbRowDeleted(String table, String keyColumn, String keyValue) {
        List<DbRow> next = new ArrayList<>(dbRows);
        next.removeIf(r -> r.table().equals(table) && r.keyColumn().equals(keyColumn)
                && r.keyValue().equals(keyValue));
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, next, httpStubs, socketSessions);
    }

    public TestRunState withHttpStubRegistered(HttpStubInfo stub) {
        List<HttpStubInfo> next = new ArrayList<>(httpStubs);
        next.add(stub);
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, dbRows, next, socketSessions);
    }

    public TestRunState withHttpStubRemoved(String stubId) {
        List<HttpStubInfo> next = new ArrayList<>(httpStubs);
        next.removeIf(s -> s.stubId().equals(stubId));
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, dbRows, next, socketSessions);
    }

    public TestRunState withSocketSessionOpened(SocketSessionInfo session) {
        List<SocketSessionInfo> next = new ArrayList<>(socketSessions);
        next.add(session);
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, dbRows, httpStubs, next);
    }

    public TestRunState withSocketSessionClosed(String sessionId) {
        List<SocketSessionInfo> next = new ArrayList<>(socketSessions);
        next.removeIf(s -> s.sessionId().equals(sessionId));
        return new TestRunState(testId, testClass, testMethod, runId,
                startedAt, endedAt, status, dbRows, httpStubs, next);
    }
}
