package io.graphrag.testlib.api;

import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import io.graphrag.testlib.internal.SqlTableParser;
import io.graphrag.testlib.spi.DashboardReporter;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.JdbcAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/** 픽스처 INSERT / cleanup DELETE 용 JDBC 접근. 연결은 lazy, cleanup 시 닫는다. */
public final class JdbcHelper {

    private static final Logger log = LoggerFactory.getLogger(JdbcHelper.class);

    private final JdbcAdapter adapter;
    private final Env env;
    private final String testId;
    private final String runId;
    private final DashboardReporter dashboard;
    private Connection connection;
    private final java.util.List<DeferredDelete> deferred = new java.util.ArrayList<>();

    private record DeferredDelete(String sql, Object[] args) {
    }

    JdbcHelper(JdbcAdapter adapter, Env env, String testId, String runId, DashboardReporter dashboard) {
        this.adapter = adapter;
        this.env = env;
        this.testId = testId;
        this.runId = runId;
        this.dashboard = dashboard;
    }

    public int update(String sql, Object... args) {
        try {
            try (PreparedStatement statement = connection().prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                int affected = statement.executeUpdate();
                reportRowEvent(sql, args);
                return affected;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("jdbc update failed: " + sql, e);
        }
    }

    /**
     * cleanup 시 실행할 DELETE를 등록 순서(FIFO)대로 보관한다. scope.cleanup()에서 실행.
     * FK 안전 정리를 위해 호출자는 자식 테이블 DELETE를 먼저 등록해야 한다(FK 역순).
     */
    public void deferDelete(String sql, Object... args) {
        deferred.add(new DeferredDelete(sql, args.clone()));
    }

    /**
     * 등록된 DELETE를 등록 순서(FIFO)대로 실행한다.
     * 개별 실패는 경고 로그를 남기고 삼켜 나머지 정리를 막지 않는다.
     */
    void runDeferredDeletes() {
        try {
            for (DeferredDelete d : deferred) {
                try {
                    update(d.sql(), d.args());
                } catch (RuntimeException e) {
                    log.warn("deferred DELETE failed (best-effort, ignored): {} — {}", d.sql(), e.getMessage());
                }
            }
        } finally {
            deferred.clear();
        }
    }

    /** 단일 long 값 조회(보통 SELECT count(*)). */
    public long queryLong(String sql, Object... args) {
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("jdbc query failed: " + sql, e);
        }
    }

    /** async consumer side-effect 대기: count 쿼리가 >0 될 때까지 폴링(타임아웃 시 false). */
    public boolean pollUntilExists(String countSql, java.time.Duration timeout, Object... args) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queryLong(countSql, args) > 0) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queryLong(countSql, args) > 0;
    }

    private void reportRowEvent(String sql, Object[] args) {
        SqlTableParser.RowRef ref = SqlTableParser.parse(sql, args);
        if (ref == null) {
            return;
        }
        EventType type = ref.kind() == SqlTableParser.Kind.INSERT
                ? EventType.DB_ROW_INSERTED
                : EventType.DB_ROW_DELETED;
        var detail = Json.mapper().createObjectNode()
                .put("table", ref.table())
                .put("keyColumn", ref.keyColumn())
                .put("keyValue", String.valueOf(ref.keyValue()));
        dashboard.report(new TestEvent(type, testId, runId, Instant.now(), detail));
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = adapter.connect(env);
        }
        return connection;
    }

    void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // cleanup 중 연결 닫기 실패는 테스트를 실패시키지 않는다
            }
            connection = null;
        }
    }
}
