package io.graphrag.dashboard.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.dashboard.domain.DbRow;
import io.graphrag.dashboard.domain.HttpStubInfo;
import io.graphrag.dashboard.domain.SocketSessionInfo;
import io.graphrag.dashboard.domain.TestRunState;
import io.graphrag.dashboard.domain.TestRunStatus;
import io.graphrag.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 메모리 기반 {@link TestRunState} 저장소 + 이벤트 처리.
 *
 * <p>thread-safe. ConcurrentHashMap + 각 testId당 atomic compute로 동시성 보장.
 */
public class TestRunRegistry {

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final ConcurrentMap<String, TestRunState> states = new ConcurrentHashMap<>();

    public void handle(DashboardEvent event) {
        String testId = event.testId();
        switch (event.type()) {
            case SCOPE_CREATED -> {
                ScopeCreatedPayload p = convert(event.payload(), ScopeCreatedPayload.class);
                states.put(testId, TestRunState.start(testId, p.testClass(), p.testMethod(),
                        p.runId(), event.timestamp()));
            }
            case SCOPE_CLEANED -> states.computeIfPresent(testId,
                    (k, v) -> v.withCleaned(event.timestamp()));
            case DB_ROW_INSERTED -> {
                DbRowInsertedPayload p = convert(event.payload(), DbRowInsertedPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withDbRowInserted(
                        new DbRow(p.table(), p.keyColumn(), p.keyValue(), event.timestamp())));
            }
            case DB_ROW_DELETED -> {
                DbRowDeletedPayload p = convert(event.payload(), DbRowDeletedPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withDbRowDeleted(
                        p.table(), p.keyColumn(), p.keyValue()));
            }
            case HTTP_STUB_REGISTERED -> {
                HttpStubRegisteredPayload p = convert(event.payload(), HttpStubRegisteredPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withHttpStubRegistered(
                        new HttpStubInfo(p.stubId(), p.urlPattern(),
                                p.scopeBaggageValue(), event.timestamp())));
            }
            case HTTP_STUB_REMOVED -> {
                HttpStubRemovedPayload p = convert(event.payload(), HttpStubRemovedPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withHttpStubRemoved(p.stubId()));
            }
            case SOCKET_SESSION_OPENED -> {
                SocketSessionOpenedPayload p = convert(event.payload(), SocketSessionOpenedPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withSocketSessionOpened(
                        new SocketSessionInfo(p.sessionId(), p.mockHost(),
                                p.mockPort(), event.timestamp())));
            }
            case SOCKET_SESSION_CLOSED -> {
                SocketSessionClosedPayload p = convert(event.payload(), SocketSessionClosedPayload.class);
                states.computeIfPresent(testId, (k, v) -> v.withSocketSessionClosed(p.sessionId()));
            }
            case AUTH_TOKEN_ISSUED -> { /* Phase 0 에선 추적만, 별도 자원 등록 없음 */ }
        }
    }

    private <T> T convert(Object payload, Class<T> type) {
        return MAPPER.convertValue(payload, type);
    }

    public Optional<TestRunState> get(String testId) {
        return Optional.ofNullable(states.get(testId));
    }

    public List<TestRunState> listActive() {
        return statesIn(TestRunStatus.ACTIVE);
    }

    public List<TestRunState> listLeaked() {
        return statesIn(TestRunStatus.LEAKED);
    }

    public List<TestRunState> all() {
        return new ArrayList<>(states.values());
    }

    private List<TestRunState> statesIn(TestRunStatus status) {
        return states.values().stream()
                .filter(s -> s.status() == status)
                .collect(Collectors.toList());
    }

    /** Leak detector가 호출. ACTIVE → LEAKED 전이. */
    public Optional<TestRunState> markLeaked(String testId) {
        return Optional.ofNullable(states.computeIfPresent(testId, (k, v) ->
                v.status() == TestRunStatus.ACTIVE ? v.withLeaked() : v));
    }

    /** 특정 테이블 행의 소유자 조회. */
    public List<TableRowHolder> tableHolders(String tableName) {
        List<TableRowHolder> result = new ArrayList<>();
        for (TestRunState s : states.values()) {
            for (DbRow row : s.dbRows()) {
                if (row.table().equals(tableName)) {
                    result.add(new TableRowHolder(row.keyValue(), s.testId(), row.insertedAt().toString()));
                }
            }
        }
        return result;
    }

    public record TableRowHolder(String keyValue, String ownerTestId, String insertedAt) {}
}
