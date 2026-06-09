package io.graphrag.testlib.api;

import io.graphrag.model.EventType;
import io.graphrag.model.Json;
import io.graphrag.model.TestEvent;
import io.graphrag.testlib.internal.SqlTableParser;
import io.graphrag.testlib.spi.DashboardReporter;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.JdbcAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/** 픽스처 INSERT / cleanup DELETE 용 JDBC 접근. 연결은 lazy, cleanup 시 닫는다. */
public final class JdbcHelper {

    private final JdbcAdapter adapter;
    private final Env env;
    private final String testId;
    private final String runId;
    private final DashboardReporter dashboard;
    private Connection connection;

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
