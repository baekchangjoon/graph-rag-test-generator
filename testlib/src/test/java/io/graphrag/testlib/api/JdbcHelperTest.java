package io.graphrag.testlib.api;

import io.graphrag.testlib.adapter.dashboard.DashboardReporters;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.JdbcAdapter;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JdbcHelperTest {

    private final Env env = Env.of(java.util.Map.of());

    // ---------- hand-rolled fakes ----------

    /** PreparedStatement stub that records the sql it was created with and invokes a callback on executeUpdate. */
    private static class FakePreparedStatement implements PreparedStatement {
        private final String sql;
        private final SqlCallback callback;

        FakePreparedStatement(String sql, SqlCallback callback) {
            this.sql = sql;
            this.callback = callback;
        }

        @Override public int executeUpdate() throws SQLException { return callback.execute(sql); }

        // --- all other methods are no-ops / stubs ---
        @Override public ResultSet executeQuery() { return null; }
        @Override public boolean execute() { return false; }
        @Override public void setObject(int i, Object x) {}
        @Override public void addBatch() {}
        @Override public void clearParameters() {}
        @Override public ResultSet executeQuery(String s) { return null; }
        @Override public int executeUpdate(String s) { return 0; }
        @Override public void close() {}
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int i) {}
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int i) {}
        @Override public void setEscapeProcessing(boolean b) {}
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int i) {}
        @Override public void cancel() {}
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public void setCursorName(String s) {}
        @Override public boolean execute(String s) { return false; }
        @Override public ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return 0; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int i) {}
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int i) {}
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String s) {}
        @Override public void clearBatch() {}
        @Override public int[] executeBatch() { return new int[0]; }
        @Override public Connection getConnection() { return null; }
        @Override public boolean getMoreResults(int i) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String s, int i) { return 0; }
        @Override public int executeUpdate(String s, int[] ints) { return 0; }
        @Override public int executeUpdate(String s, String[] strings) { return 0; }
        @Override public boolean execute(String s, int i) { return false; }
        @Override public boolean execute(String s, int[] ints) { return false; }
        @Override public boolean execute(String s, String[] strings) { return false; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean b) {}
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() {}
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> c) { return null; }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
        @Override public void setNull(int i, int j) {}
        @Override public void setBoolean(int i, boolean b) {}
        @Override public void setByte(int i, byte b) {}
        @Override public void setShort(int i, short s) {}
        @Override public void setInt(int i, int j) {}
        @Override public void setLong(int i, long l) {}
        @Override public void setFloat(int i, float v) {}
        @Override public void setDouble(int i, double v) {}
        @Override public void setBigDecimal(int i, java.math.BigDecimal bd) {}
        @Override public void setString(int i, String s) {}
        @Override public void setBytes(int i, byte[] bytes) {}
        @Override public void setDate(int i, java.sql.Date d) {}
        @Override public void setTime(int i, java.sql.Time t) {}
        @Override public void setTimestamp(int i, java.sql.Timestamp ts) {}
        @Override public void setAsciiStream(int i, java.io.InputStream is, int l) {}
        @Override public void setUnicodeStream(int i, java.io.InputStream is, int l) {}
        @Override public void setBinaryStream(int i, java.io.InputStream is, int l) {}
        @Override public void setObject(int i, Object x, int t) {}
        @Override public void setCharacterStream(int i, java.io.Reader r, int l) {}
        @Override public void setRef(int i, java.sql.Ref ref) {}
        @Override public void setBlob(int i, java.sql.Blob b) {}
        @Override public void setClob(int i, java.sql.Clob c) {}
        @Override public void setArray(int i, java.sql.Array a) {}
        @Override public java.sql.ResultSetMetaData getMetaData() { return null; }
        @Override public void setDate(int i, java.sql.Date d, java.util.Calendar cal) {}
        @Override public void setTime(int i, java.sql.Time t, java.util.Calendar cal) {}
        @Override public void setTimestamp(int i, java.sql.Timestamp ts, java.util.Calendar cal) {}
        @Override public void setNull(int i, int t, String s) {}
        @Override public void setURL(int i, java.net.URL u) {}
        @Override public java.sql.ParameterMetaData getParameterMetaData() { return null; }
        @Override public void setRowId(int i, java.sql.RowId r) {}
        @Override public void setNString(int i, String v) {}
        @Override public void setNCharacterStream(int i, java.io.Reader r, long l) {}
        @Override public void setNClob(int i, java.sql.NClob nc) {}
        @Override public void setClob(int i, java.io.Reader r, long l) {}
        @Override public void setBlob(int i, java.io.InputStream is, long l) {}
        @Override public void setNClob(int i, java.io.Reader r, long l) {}
        @Override public void setSQLXML(int i, java.sql.SQLXML sx) {}
        @Override public void setObject(int i, Object x, int t, int s) {}
        @Override public void setAsciiStream(int i, java.io.InputStream is, long l) {}
        @Override public void setBinaryStream(int i, java.io.InputStream is, long l) {}
        @Override public void setCharacterStream(int i, java.io.Reader r, long l) {}
        @Override public void setAsciiStream(int i, java.io.InputStream is) {}
        @Override public void setBinaryStream(int i, java.io.InputStream is) {}
        @Override public void setCharacterStream(int i, java.io.Reader r) {}
        @Override public void setNCharacterStream(int i, java.io.Reader r) {}
        @Override public void setClob(int i, java.io.Reader r) {}
        @Override public void setBlob(int i, java.io.InputStream is) {}
        @Override public void setNClob(int i, java.io.Reader r) {}
    }

    @FunctionalInterface
    interface SqlCallback {
        int execute(String sql) throws SQLException;
    }

    /** Connection stub that returns a FakePreparedStatement for each prepareStatement call. */
    private static class FakeConnection implements Connection {
        private final SqlCallback callback;

        FakeConnection(SqlCallback callback) {
            this.callback = callback;
        }

        @Override public PreparedStatement prepareStatement(String sql) {
            return new FakePreparedStatement(sql, callback);
        }

        // --- stubs for unused methods ---
        @Override public java.sql.Statement createStatement() { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s) { return null; }
        @Override public String nativeSQL(String s) { return s; }
        @Override public void setAutoCommit(boolean b) {}
        @Override public boolean getAutoCommit() { return true; }
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public void close() {}
        @Override public boolean isClosed() { return false; }
        @Override public java.sql.DatabaseMetaData getMetaData() { return null; }
        @Override public void setReadOnly(boolean b) {}
        @Override public boolean isReadOnly() { return false; }
        @Override public void setCatalog(String s) {}
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int i) {}
        @Override public int getTransactionIsolation() { return 0; }
        @Override public SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public java.sql.Statement createStatement(int i, int j) { return null; }
        @Override public PreparedStatement prepareStatement(String s, int i, int j) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s, int i, int j) { return null; }
        @Override public java.util.Map<String, Class<?>> getTypeMap() { return null; }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) {}
        @Override public void setHoldability(int h) {}
        @Override public int getHoldability() { return 0; }
        @Override public java.sql.Savepoint setSavepoint() { return null; }
        @Override public java.sql.Savepoint setSavepoint(String s) { return null; }
        @Override public void rollback(java.sql.Savepoint sp) {}
        @Override public void releaseSavepoint(java.sql.Savepoint sp) {}
        @Override public java.sql.Statement createStatement(int i, int j, int k) { return null; }
        @Override public PreparedStatement prepareStatement(String s, int i, int j, int k) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String s, int i, int j, int k) { return null; }
        @Override public PreparedStatement prepareStatement(String s, int i) { return null; }
        @Override public PreparedStatement prepareStatement(String s, int[] ints) { return null; }
        @Override public PreparedStatement prepareStatement(String s, String[] strings) { return null; }
        @Override public java.sql.Clob createClob() { return null; }
        @Override public java.sql.Blob createBlob() { return null; }
        @Override public java.sql.NClob createNClob() { return null; }
        @Override public java.sql.SQLXML createSQLXML() { return null; }
        @Override public boolean isValid(int i) { return true; }
        @Override public void setClientInfo(String k, String v) {}
        @Override public void setClientInfo(java.util.Properties p) {}
        @Override public String getClientInfo(String k) { return null; }
        @Override public java.util.Properties getClientInfo() { return null; }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) { return null; }
        @Override public java.sql.Struct createStruct(String t, Object[] a) { return null; }
        @Override public void setSchema(String s) {}
        @Override public String getSchema() { return null; }
        @Override public void abort(java.util.concurrent.Executor e) {}
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int ms) {}
        @Override public int getNetworkTimeout() { return 0; }
        @Override public <T> T unwrap(Class<T> c) { return null; }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
    }

    /** JdbcAdapter fake that returns a given Connection. */
    private static class FakeJdbcAdapter implements JdbcAdapter {
        private final Connection conn;
        FakeJdbcAdapter(Connection conn) { this.conn = conn; }
        @Override public Connection connect(io.graphrag.testlib.spi.Env env) { return conn; }
        @Override public String name() { return "fake"; }
    }

    private JdbcHelper helper(Connection conn) {
        return new JdbcHelper(new FakeJdbcAdapter(conn), env, "t-test", "run-test",
                DashboardReporters.fromEnv(env));
    }

    // ---------- tests ----------

    @Test
    void runsDeferredDeletesInRegistrationOrder() throws Exception {
        List<String> executed = new ArrayList<>();
        Connection conn = new FakeConnection(sql -> { executed.add(sql); return 1; });
        JdbcHelper h = helper(conn);
        h.deferDelete("DELETE FROM orders WHERE user_id = ?", "u1");
        h.deferDelete("DELETE FROM users WHERE id = ?", "u1");
        h.runDeferredDeletes();
        assertThat(executed).containsExactly(
                "DELETE FROM orders WHERE user_id = ?",
                "DELETE FROM users WHERE id = ?");
    }

    @Test
    void deferredDeleteFailureIsBestEffort() throws Exception {
        List<String> attempted = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        Connection conn = new FakeConnection(sql -> {
            attempted.add(sql);
            if (sql.contains("orders")) throw new SQLException("boom");
            executed.add(sql);
            return 1;
        });
        JdbcHelper h = helper(conn);
        h.deferDelete("DELETE FROM orders WHERE user_id = ?", "u1");
        h.deferDelete("DELETE FROM users WHERE id = ?", "u1");
        assertThatCode(h::runDeferredDeletes).doesNotThrowAnyException();
        assertThat(attempted).containsExactly(
                "DELETE FROM orders WHERE user_id = ?",
                "DELETE FROM users WHERE id = ?");
        assertThat(executed).containsExactly("DELETE FROM users WHERE id = ?");
    }

    @Test
    void runDeferredDeletesOnEmptyIsNoop() {
        JdbcHelper h = helper(new FakeConnection(sql -> 1));
        assertThatCode(h::runDeferredDeletes).doesNotThrowAnyException();
    }
}
