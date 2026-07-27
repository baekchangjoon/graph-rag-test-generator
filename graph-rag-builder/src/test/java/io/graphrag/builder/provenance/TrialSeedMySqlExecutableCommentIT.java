package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N1 회귀(리뷰 Critical, 실 MySQL 8.0 컨테이너로 실측된 우회): MySQL/MariaDB <b>실행형 주석</b>
 * ({@code /*!... &#42;/})은 JSqlParser가 주석으로 버리지만 MySQL 서버는 실행한다. 후보 원문 줄을
 * {@code Statement.execute}로 보내면 T1 마커-diff·allowlist·정리 계획 어디에도 보이지 않는 행이
 * 삽입되고, 정리 DELETE는 추적된 PK만 지우므로 그 행은 영속 잔존한다.
 *
 * <p>고치기 전 실측(리뷰어 재현):
 * <pre>
 * 후보: INSERT INTO fund_accounts (id, balance_amount) VALUES ('seed-x', 1) /*!10000 , ('evil', 999) &#42;/;
 * JSqlParser: values=[StringValue 'seed-x', LongValue 1]   ← base와 동일 판정
 * MySQL 실행 후: seed-x|1, evil|999 → 정리 후 evil|999 영속 잔존
 * </pre>
 *
 * <p>이 IT는 {@link TrialRunner}가 <b>파싱 결과에서 재생성한 파라미터화 INSERT</b>만 실행함을
 * 실 MySQL에서 확인한다 — 삽입되는 행은 파서가 본 1행뿐이고, 정리 후 잔존 행은 0이다.
 * 컨테이너는 Testcontainers가 소유하며(Ryuk이 강제종료 경로에서도 reap) 테스트당 하나만 띄운다.
 */
@Testcontainers
@Tag("docker")
class TrialSeedMySqlExecutableCommentIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("trialdb")
            .withLabel("test-run", "trial-seed-executable-comment");

    private static final AtomicInteger SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    private static final Endpoint ENDPOINT =
            new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);

    private static final ProvenanceReport EMPTY_REPORT =
            new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of());

    private static class SilentSutHandle implements SutHandle {
        @Override public String baseUri() { return "http://fake"; }
        @Override public long logOffset() { return 0; }
        @Override public String readLog() { return ""; }
        @Override public String readLogFrom(long offset) { return ""; }
        @Override public String readLogRange(long start, long end) { return ""; }
        @Override public void stop() { }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private Path candidate(String seedSql) throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-" + SEQ.incrementAndGet()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 1);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), seedSql);
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        return candDir;
    }

    private static TrialRunner runner(Connection connection, TrialRunner.TrialInvoker invoker) {
        return new TrialRunner(connection, DbConfig.Type.MYSQL, null,
                new StatusOnlyClassifier(), new SilentSutHandle(), invoker);
    }

    private static long count(Connection connection, String query) throws Exception {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("N1: MySQL 실행형 주석이 붙은 후보도 파서가 본 1행만 삽입되고 정리 후 잔존 0")
    void executableCommentRowIsNeverInserted() throws Exception {
        try (Connection connection = connect()) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS fund_accounts");
                st.execute("CREATE TABLE fund_accounts "
                        + "(id VARCHAR(50) PRIMARY KEY, balance_amount BIGINT)");
            }
            Path candDir = candidate("INSERT INTO fund_accounts (id, balance_amount) "
                    + "VALUES ('seed-x', 1) /*!10000 , ('evil', 999) */;");

            // invoke 시점(= seed INSERT 직후, 정리 직전)의 실제 행 수를 관측한다.
            AtomicLong rowsDuringInvoke = new AtomicLong(-1);
            AtomicLong evilRowsDuringInvoke = new AtomicLong(-1);
            TrialRunner.TrialInvoker invoker = (endpoint, body) -> {
                rowsDuringInvoke.set(count(connection, "SELECT COUNT(*) FROM fund_accounts"));
                evilRowsDuringInvoke.set(
                        count(connection, "SELECT COUNT(*) FROM fund_accounts WHERE id = 'evil'"));
                return new InvocationOutcome(200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            };

            TrialRunner.TrialOutcome outcome =
                    runner(connection, invoker).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            assertThat(evilRowsDuringInvoke.get())
                    .as("실행형 주석 안의 행('evil')은 파서가 보지 못했으므로 삽입되어서는 안 된다")
                    .isZero();
            assertThat(rowsDuringInvoke.get())
                    .as("삽입되는 행은 파서가 본 1행뿐이어야 한다").isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM fund_accounts"))
                    .as("정리 후 잔존 행은 0이어야 한다").isZero();
        }
    }

    @Test
    @DisplayName("N1: 실행형 주석 ON DUPLICATE KEY UPDATE도 allowlist를 우회해 실행되지 않는다")
    void executableCommentDuplicateKeyUpdateIsNotExecuted() throws Exception {
        try (Connection connection = connect()) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS fund_accounts_dup");
                st.execute("CREATE TABLE fund_accounts_dup "
                        + "(id VARCHAR(50) PRIMARY KEY, balance_amount BIGINT)");
                st.execute("INSERT INTO fund_accounts_dup (id, balance_amount) VALUES ('seed-x', 1)");
            }
            Path candDir = candidate("INSERT INTO fund_accounts_dup (id, balance_amount) "
                    + "VALUES ('seed-x', 2) /*!10000 ON DUPLICATE KEY UPDATE balance_amount = 999 */;");

            TrialRunner.TrialInvoker invoker = (endpoint, body) ->
                    new InvocationOutcome(200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);

            // 재생성 INSERT는 ON DUPLICATE KEY UPDATE 절이 없으므로 PK 충돌로 실패한다 —
            // 후보가 실패하는 것이 정상이며, 기존 행이 조용히 변조되지 않는 것이 핵심이다.
            assertThatThrownBy(() ->
                    runner(connection, invoker).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT))
                    .isInstanceOf(java.sql.SQLException.class);

            assertThat(count(connection,
                    "SELECT COUNT(*) FROM fund_accounts_dup WHERE id = 'seed-x' AND balance_amount = 1"))
                    .as("실행형 주석의 ON DUPLICATE KEY UPDATE가 실행되면 balance_amount가 999로 바뀐다")
                    .isEqualTo(1);
        }
    }
}
