package io.graphrag.builder.run;

import io.graphrag.builder.capture.ParsedSql;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.TableSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-012 M2: tryDeleteInsertRow / resolvePkColumn / findBindingValueForColumn 단위 테스트.
 *
 * <p>PK 해석이 컬럼 위치(position 1) 가정이 아닌 스키마 기반임을 검증하고,
 * 0-rows-deleted → false, INSERT 없음 → false 동작을 확인한다(C2 fix).
 */
@DisplayName("REQ-012 C2: kafka-diff INSERT 정리 — 스키마 PK 기반 삭제")
class KafkaDiffCleanupTest {

    /** orders 테이블: PK = order_id (컬럼 목록의 세 번째 — position 1이 PK가 아님). */
    private static final TableSchema ORDERS_SCHEMA = new TableSchema(
            "orders",
            List.of(
                    new ColumnSchema("customer_id", "BIGINT", false, false),
                    new ColumnSchema("status", "VARCHAR", false, false),
                    new ColumnSchema("order_id", "BIGINT", false, true)
            ),
            List.of(),
            List.of());

    private Connection connection;
    private EndpointExplorationRunner runner;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:kafka_diff_cleanup_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        connection = ds.getConnection();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE orders (
                        customer_id BIGINT NOT NULL,
                        status VARCHAR(50) NOT NULL,
                        order_id BIGINT NOT NULL PRIMARY KEY
                    )
                    """);
        }
        // EndpointExplorationRunner 생성자는 의존성이 많으므로 정적 헬퍼를 직접 테스트한다.
        runner = null;  // 사용 안 함 — 정적/package-private 메서드를 직접 호출
    }

    // ── resolvePkColumn ──────────────────────────────────────────────────────

    @Test
    @DisplayName("REQ-012 C2: PK 컬럼은 스키마에서 조회 — 위치가 아닌 primaryKey 플래그 기반")
    void resolvePkColumn_returnsActualPkColumn_notFirstColumn() {
        String pk = EndpointExplorationRunner.resolvePkColumn("orders", List.of(ORDERS_SCHEMA));
        // customer_id(position 1)가 아니라 order_id(position 3)이어야 한다.
        assertThat(pk).isEqualTo("order_id");
    }

    @Test
    @DisplayName("REQ-012 C2: 테이블명 비교는 대소문자 무시")
    void resolvePkColumn_caseInsensitiveTableMatch() {
        String pk = EndpointExplorationRunner.resolvePkColumn("ORDERS", List.of(ORDERS_SCHEMA));
        assertThat(pk).isEqualTo("order_id");
    }

    @Test
    @DisplayName("REQ-012 C2: 테이블 스키마 없음 → null 반환 (보수적 skip)")
    void resolvePkColumn_unknownTable_returnsNull() {
        String pk = EndpointExplorationRunner.resolvePkColumn("unknown_table", List.of(ORDERS_SCHEMA));
        assertThat(pk).isNull();
    }

    @Test
    @DisplayName("REQ-012 C2: tables가 null → null 반환")
    void resolvePkColumn_nullTables_returnsNull() {
        String pk = EndpointExplorationRunner.resolvePkColumn("orders", null);
        assertThat(pk).isNull();
    }

    // ── findBindingValueForColumn ────────────────────────────────────────────

    @Test
    @DisplayName("REQ-012 C2: INSERT의 세 번째 컬럼(order_id)에서 바인딩 값을 추출한다")
    void findBindingValueForColumn_findsValueByColumnName_notByPosition() {
        // INSERT INTO orders (customer_id, status, order_id) VALUES (?, ?, ?)
        ParsedSql sql = new ParsedSql(
                "insert into orders (customer_id, status, order_id) values (?, ?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "100"),
                        new ParsedSql.Binding(2, "PENDING"),
                        new ParsedSql.Binding(3, "42")
                ));
        String pkVal = EndpointExplorationRunner.findBindingValueForColumn(sql, "order_id");
        assertThat(pkVal).isEqualTo("42");
    }

    @Test
    @DisplayName("REQ-012 C2: PK 컬럼명 비교는 대소문자 무시")
    void findBindingValueForColumn_caseInsensitiveColumnMatch() {
        ParsedSql sql = new ParsedSql(
                "insert into orders (customer_id, status, order_id) values (?, ?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "100"),
                        new ParsedSql.Binding(2, "PENDING"),
                        new ParsedSql.Binding(3, "42")
                ));
        String pkVal = EndpointExplorationRunner.findBindingValueForColumn(sql, "ORDER_ID");
        assertThat(pkVal).isEqualTo("42");
    }

    @Test
    @DisplayName("REQ-012 C2: PK 컬럼이 INSERT 컬럼 목록에 없으면 null 반환 (auto-increment 신호)")
    void findBindingValueForColumn_pkNotInInsert_returnsNull() {
        // Hibernate auto-increment: INSERT INTO orders (amount, status, type, user_id) — PK not in list
        ParsedSql sql = new ParsedSql(
                "insert into orders (amount, status, type, user_id) values (?, ?, ?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "1"),
                        new ParsedSql.Binding(2, "PENDING"),
                        new ParsedSql.Binding(3, "EXPRESS"),
                        new ParsedSql.Binding(4, "probe-userId")
                ));
        String pkVal = EndpointExplorationRunner.findBindingValueForColumn(sql, "order_id");
        assertThat(pkVal).isNull();
    }

    // ── tryDeleteInsertRow (integration against H2) ──────────────────────────

    @Test
    @DisplayName("REQ-012 C2: 행이 존재하면 정확히 삭제하고 true 반환")
    void tryDeleteInsertRow_rowExists_deletesAndReturnsTrue() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO orders (customer_id, status, order_id) VALUES (1, 'NEW', 99)");
        }
        ParsedSql sql = new ParsedSql(
                "insert into orders (customer_id, status, order_id) values (?, ?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "1"),
                        new ParsedSql.Binding(2, "NEW"),
                        new ParsedSql.Binding(3, "99")
                ));

        EndpointExplorationRunnerAccessor accessor = new EndpointExplorationRunnerAccessor(connection);
        boolean result = accessor.callTryDeleteInsertRow(List.of(sql), List.of(ORDERS_SCHEMA));

        assertThat(result).isTrue();
        int remaining = countRows();
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("REQ-012 C2: 0 rows deleted → false (행 없음은 실패로 간주)")
    void tryDeleteInsertRow_noMatchingRow_returnsFalse() {
        // DB에 행 없음 — DELETE hits 0 rows
        ParsedSql sql = new ParsedSql(
                "insert into orders (customer_id, status, order_id) values (?, ?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "1"),
                        new ParsedSql.Binding(2, "NEW"),
                        new ParsedSql.Binding(3, "999")  // row that doesn't exist
                ));

        EndpointExplorationRunnerAccessor accessor = new EndpointExplorationRunnerAccessor(connection);
        boolean result = accessor.callTryDeleteInsertRow(List.of(sql), List.of(ORDERS_SCHEMA));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("REQ-012 C2: SQL 목록에 INSERT 없음 → false 반환 (skip 신호)")
    void tryDeleteInsertRow_noInsertInSqlList_returnsFalse() {
        ParsedSql selectSql = new ParsedSql(
                "select order_id from orders where order_id = ?",
                List.of(new ParsedSql.Binding(1, "1")));

        EndpointExplorationRunnerAccessor accessor = new EndpointExplorationRunnerAccessor(connection);
        boolean result = accessor.callTryDeleteInsertRow(List.of(selectSql), List.of(ORDERS_SCHEMA));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("REQ-012 C2: 스키마에 테이블 없음 → false 반환 (PK 해석 불가)")
    void tryDeleteInsertRow_tableNotInSchema_returnsFalse() {
        ParsedSql sql = new ParsedSql(
                "insert into unknown_table (id, name) values (?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "1"),
                        new ParsedSql.Binding(2, "foo")
                ));

        EndpointExplorationRunnerAccessor accessor = new EndpointExplorationRunnerAccessor(connection);
        boolean result = accessor.callTryDeleteInsertRow(List.of(sql), List.of(ORDERS_SCHEMA));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("REQ-012 C2: PK가 INSERT 목록에 없음(auto-increment) → true 반환 (정리 불필요, diff 진행)")
    void tryDeleteInsertRow_autoIncrementPk_returnsTrue() {
        // Hibernate auto-increment: INSERT INTO orders (customer_id, status) — order_id(PK) 없음
        ParsedSql sql = new ParsedSql(
                "insert into orders (customer_id, status) values (?, ?)",
                List.of(
                        new ParsedSql.Binding(1, "100"),
                        new ParsedSql.Binding(2, "PENDING")
                ));

        EndpointExplorationRunnerAccessor accessor = new EndpointExplorationRunnerAccessor(connection);
        boolean result = accessor.callTryDeleteInsertRow(List.of(sql), List.of(ORDERS_SCHEMA));

        // auto-increment PK: 2차 invoke는 새 PK를 받으므로 정리 불필요 → true
        assertThat(result).isTrue();
    }

    private int countRows() throws SQLException {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * EndpointExplorationRunner의 package-private tryDeleteInsertRow를 테스트에서 호출하기 위한
     * 동일 패키지 내 래퍼. Connection만 주입하고 나머지 의존성은 null(사용 안 함).
     */
    static class EndpointExplorationRunnerAccessor extends EndpointExplorationRunner {

        EndpointExplorationRunnerAccessor(Connection connection) {
            super(/* sut */ null, connection, /* dbType */ null,
                    /* coverage */ null, /* analyzer */ null, /* budgetRequests */ 0,
                    /* httpCapture */ null, /* responseDtoFieldSets */ List.of(),
                    /* literalCandidates */ List.of(),
                    /* authProvider */ null, /* authConfig */ null,
                    /* enumConstants */ java.util.Map.of(),
                    /* enumColumns */ java.util.Map.of(),
                    /* extraHeaders */ null,
                    /* sqlCapture */ null,
                    /* kafkaCapture */ null);
        }

        boolean callTryDeleteInsertRow(List<io.graphrag.builder.capture.ParsedSql> sqlList,
                                       List<io.graphrag.model.TableSchema> tables) {
            return tryDeleteInsertRow(sqlList, tables);
        }
    }
}
