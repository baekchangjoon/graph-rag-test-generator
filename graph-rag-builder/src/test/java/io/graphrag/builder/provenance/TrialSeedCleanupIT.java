package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 회귀(리뷰 Critical 2·3): {@link TrialRunner}의 후보 seed 정리(역-DELETE)가 <b>PK 스키마 사실</b>로만
 * 키를 결정하고, 식별자·값을 후보 텍스트에서 문자열 결합하지 않음을 고정한다.
 *
 * <p>고치기 전의 결함:
 * <ul>
 *   <li>정리 DELETE가 {@code columns.get(0)}을 PK로 <b>가정</b>해, 컬럼 순서가 뒤바뀐 후보에서는
 *       {@code DELETE FROM t WHERE <비-PK 컬럼> = <값>}이 나가 <b>조건에 맞는 모든 행</b>이 삭제됐다.</li>
 *   <li>DELETE가 테이블·컬럼 식별자를 문자열로 이어 붙여, 인용 식별자에 숨긴 문장이 그대로 실행됐다.</li>
 *   <li>컬럼 목록 없는 {@code INSERT INTO t VALUES (...)}는 추적 가드를 빠져나가 정리 대상에서 누락됐다.</li>
 * </ul>
 * 실 DB 대신 H2 in-memory를 쓴다(Docker 불필요 — {@link TrialDigestIT}와 동일 관례).
 */
class TrialSeedCleanupIT {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

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

    private Connection newH2Connection(String... ddl) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:trial-cleanup-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = connection.createStatement()) {
            for (String statement : ddl) {
                st.execute(statement);
            }
        }
        return connection;
    }

    private Path candidate(String seedSql) throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-" + DB_SEQ.get()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 1);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), seedSql);
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        return candDir;
    }

    private static TrialRunner runner(Connection connection) {
        TrialRunner.TrialInvoker invoker = (endpoint, body) -> new InvocationOutcome(
                200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
        return new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                new StatusOnlyClassifier(), new SilentSutHandle(), invoker);
    }

    private static long count(Connection connection, String query) throws Exception {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("C4-2: 컬럼 순서가 PK-우선이 아닌 후보도 정리 DELETE는 PK 기준으로만 나가 무관한 행을 지우지 않는다")
    void cleanupUsesPrimaryKeyNotFirstColumn() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)")) {
            try (Statement st = connection.createStatement()) {
                // 후보와 무관한 기존 행 — balance 값이 후보와 같다(첫 컬럼 기준 DELETE면 함께 삭제된다).
                st.execute("INSERT INTO accounts (id, balance) VALUES ('bystander-1', 1)");
                st.execute("INSERT INTO accounts (id, balance) VALUES ('bystander-2', 1)");
            }
            Path candDir = candidate("INSERT INTO accounts (balance, id) VALUES (1, 'acc-1');");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).as("200 응답이므로 승격 판정이어야 한다").isTrue();
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts WHERE id LIKE 'bystander%'"))
                    .as("PK가 아닌 컬럼(balance) 기준 DELETE가 나가면 무관한 행 2건이 함께 지워진다")
                    .isEqualTo(2);
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts WHERE id = 'acc-1'"))
                    .as("후보가 삽입한 행은 PK 기준으로 정리되어야 한다").isZero();
        }
    }

    @Test
    @DisplayName("C4-2: PK가 없는 테이블 대상 후보는 삭제를 시도하지 않고 fail-closed로 차단된다(DB 쓰기 0)")
    void tableWithoutPrimaryKeyIsBlockedBeforeAnyWrite() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE audit_log (message VARCHAR(50), amount BIGINT)")) {
            Path candDir = candidate("INSERT INTO audit_log (message, amount) VALUES ('x', 1);");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).as("PK 미상이면 승격이 차단되어야 한다").isFalse();
            assertThat(outcome.digest()).isNotNull();
            assertThat(outcome.digest().outcomeKind()).isEqualTo("SEED_CLEANUP_UNRESOLVABLE");
            assertThat(count(connection, "SELECT COUNT(*) FROM audit_log"))
                    .as("차단은 INSERT 이전에 일어나야 한다(DB 부작용 0)").isZero();
        }
    }

    @Test
    @DisplayName("C4-3: 인용 식별자에 숨긴 다중 문장은 정리 DELETE로 실행되지 않고 fail-closed로 차단된다")
    void quotedIdentifierInjectionIsBlockedInsteadOfExecuted() throws Exception {
        String injected = "accounts\"; DROP TABLE victim; --";
        try (Connection connection = newH2Connection(
                "CREATE TABLE victim (id VARCHAR(50) PRIMARY KEY)",
                "CREATE TABLE \"" + injected.replace("\"", "\"\"") + "\" (id VARCHAR(50) PRIMARY KEY)")) {
            Path candDir = candidate(
                    "INSERT INTO \"" + injected.replace("\"", "\"\"") + "\" (id) VALUES ('a');");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).as("안전하지 않은 식별자는 차단되어야 한다").isFalse();
            assertThat(outcome.digest().outcomeKind()).isEqualTo("SEED_CLEANUP_UNRESOLVABLE");
            assertThat(count(connection, "SELECT COUNT(*) FROM victim"))
                    .as("victim 테이블이 살아 있어야 한다(DROP 미실행)").isZero();
        }
    }

    @Test
    @DisplayName("C4-3: 컬럼 목록 없는 INSERT INTO t VALUES (...)는 정리 추적 불가로 차단된다")
    void columnLessInsertIsBlocked() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)")) {
            Path candDir = candidate("INSERT INTO accounts VALUES ('acc-1', 1);");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isFalse();
            assertThat(outcome.digest().outcomeKind()).isEqualTo("SEED_CLEANUP_UNRESOLVABLE");
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts"))
                    .as("차단은 INSERT 이전에 일어나야 한다").isZero();
        }
    }

    @Test
    @DisplayName("C4-2(회귀 0): 통상적인 PK-우선 후보는 기존과 동일하게 적용되고 정리된다")
    void normalCandidateStillAppliedAndCleanedUp() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)")) {
            Path candDir = candidate("INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts")).isZero();
        }
    }
}
