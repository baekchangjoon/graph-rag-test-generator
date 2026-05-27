package io.graphrag.builder.capture;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.CapturedSqlType;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Option A (docs/12) — CapturedSqlListener 의 SELECT row snapshot 검증.
 */
class CapturedSqlListenerTest {

    private ProxyDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:listener_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        ds = (ProxyDataSource) ProxyDataSourceBuilder.create((DataSource) h2)
                .name("test")
                .listener(new CapturedSqlListener())
                .build();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE owners (id INT PRIMARY KEY, first_name VARCHAR(255), last_name VARCHAR(255))");
            s.execute("INSERT INTO owners(id, first_name, last_name) VALUES (1, 'George', 'Franklin')");
            s.execute("INSERT INTO owners(id, first_name, last_name) VALUES (2, 'Betty', 'Davis')");
        }
    }

    @AfterEach
    void tearDown() { CaptureContext.clear(); }

    @Test
    void capturesSelectWithRowSnapshotWhenCaptureContextActive() throws Exception {
        CaptureContext.set(new CaptureContext("p-1"));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT first_name FROM owners WHERE id = ?")) {
            ps.setInt(1, 1);
            ps.executeQuery().close();
        }

        List<CapturedSql> captured = CaptureContext.current().capturedSql();
        assertThat(captured).hasSize(1);
        CapturedSql sql = captured.get(0);
        assertThat(sql.type()).isEqualTo(CapturedSqlType.SELECT);
        assertThat(sql.readResultRows()).hasSize(1);

        Map<String, Object> row = sql.readResultRows().get(0);
        // SELECT * 변환으로 ID 포함 모든 컬럼이 와야 함
        assertThat(row).containsEntry("FIRST_NAME", "George");
        assertThat(row).containsKeys("ID", "FIRST_NAME", "LAST_NAME");
    }

    @Test
    void multipleRowsCapturedForRangeSelect() throws Exception {
        CaptureContext.set(new CaptureContext("p-2"));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM owners WHERE id <= ?")) {
            ps.setInt(1, 10);
            ps.executeQuery().close();
        }

        CapturedSql sql = CaptureContext.current().capturedSql().get(0);
        assertThat(sql.readResultRows()).hasSize(2);
    }

    @Test
    void selectWithoutCaptureContextIsNoop() throws Exception {
        // CaptureContext 미설정 — listener 무시
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM owners WHERE id = ?")) {
            ps.setInt(1, 1);
            ps.executeQuery().close();
        }
        // current() 가 null 이면 capturedSql() 호출조차 불가 → 단지 예외 안 던지면 OK
        assertThat(CaptureContext.current()).isNull();
    }

    @Test
    void insertDoesNotTriggerSnapshot() throws Exception {
        CaptureContext.set(new CaptureContext("p-3"));
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO owners(id, first_name, last_name) VALUES (?, ?, ?)")) {
            ps.setInt(1, 100); ps.setString(2, "X"); ps.setString(3, "Y");
            ps.executeUpdate();
        }
        CapturedSql sql = CaptureContext.current().capturedSql().get(0);
        assertThat(sql.type()).isEqualTo(CapturedSqlType.INSERT);
        assertThat(sql.readResultRows()).isEmpty();
    }

    @Test
    void snapshotFailureDoesNotBreakOriginalQuery() throws Exception {
        // SQL 자체는 정상이나 rebuildAsSelectStar 가 매칭하지 않는 (subquery) 형태
        CaptureContext.set(new CaptureContext("p-4"));
        String complex = "SELECT id FROM (SELECT id FROM owners) WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(complex)) {
            ps.setInt(1, 1);
            ps.executeQuery().close();
        }
        CapturedSql sql = CaptureContext.current().capturedSql().get(0);
        assertThat(sql.type()).isEqualTo(CapturedSqlType.SELECT);
        // 복잡한 SQL 도 best-effort 로 snapshot 시도. 성공/실패 무관하게 type 만 검증.
        // 정규식이 매칭 안 하면 원본 SQL 그대로 재실행 — 이 경우 성공 (subquery alias 부재로 H2가 받음)
    }

    @Test
    void rebuildAsSelectStarTransformsSimpleSelect() {
        String out = CapturedSqlListener.rebuildAsSelectStar(
                "SELECT first_name, last_name FROM owners WHERE id = ?");
        assertThat(out).isEqualTo("SELECT * FROM owners WHERE id = ?");
    }

    @Test
    void rebuildAsSelectStarPreservesOriginalForJoin() {
        String original = "SELECT o.first_name FROM owners o JOIN pets p ON p.owner_id = o.id WHERE o.id = ?";
        String out = CapturedSqlListener.rebuildAsSelectStar(original);
        // JOIN 은 정규식 그룹2 (WHERE 이전 토큰) 가 단일 식별자 아니라서 원본 보존
        assertThat(out).isEqualTo(original);
    }

    @Test
    void rebuildAsSelectStarStripsOrderByAndLimit() {
        String out = CapturedSqlListener.rebuildAsSelectStar(
                "SELECT id FROM owners WHERE id > ? ORDER BY id LIMIT 10");
        assertThat(out).isEqualTo("SELECT * FROM owners WHERE id > ?");
    }
}
