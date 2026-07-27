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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1 회귀(리뷰 Critical): {@link TrialRunner}가 후보 {@code seed.sql}의 <b>원문 줄</b>을 DB로 보내지
 * 않고, <b>화이트리스트 파서가 실제로 본 파싱 결과에서 재생성한 파라미터화 INSERT</b>만 실행함을
 * 고정한다(파서가 본 것 == DB가 실행하는 것).
 *
 * <p>고치기 전의 결함: {@code insertCandidateSeed}가 {@code Statement.execute(원문 줄)}을 호출해,
 * JSqlParser가 주석으로 버리는 MySQL/MariaDB 실행형 주석({@code /*!...&#42;/})의 내용이 서버에서는
 * 실행됐다 — T1 마커-diff·allowlist·정리 계획 어디에도 보이지 않는 행이 삽입되고, 정리 DELETE는
 * 추적된 PK만 지우므로 그 행이 영속 잔존했다.
 *
 * <p>여기서는 방언 무관하게 "무엇이 DB로 나갔는가"를 JDBC 프록시로 직접 관측한다 — 실제 MySQL
 * 서버에서의 우회 재현·차단은 {@code TrialSeedMySqlExecutableCommentIT}(Docker)가 담당한다.
 */
class TrialSeedNormalizedExecutionIT {

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

    /**
     * {@link Connection} 동적 프록시 — {@code prepareStatement(sql)}로 넘어간 SQL과,
     * {@code createStatement()}가 만든 {@link Statement}에 문자열로 넘어간 SQL을 각각 기록한다.
     */
    private static final class RecordingConnection implements InvocationHandler {

        private final Connection delegate;
        private final List<String> preparedSql = new ArrayList<>();
        private final List<String> rawStatementSql = new ArrayList<>();

        private RecordingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        static Connection wrap(Connection delegate, List<RecordingConnection> out) {
            RecordingConnection handler = new RecordingConnection(delegate);
            out.add(handler);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(method, args);
            if (method.getName().equals("prepareStatement") && args != null
                    && args.length > 0 && args[0] instanceof String sql) {
                preparedSql.add(sql);
            }
            if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, (p, m, a) -> {
                            if (m.getName().startsWith("execute") && a != null
                                    && a.length > 0 && a[0] instanceof String sql) {
                                rawStatementSql.add(sql);
                            }
                            try {
                                return m.invoke(statement, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
            return result;
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private Connection newH2Connection(String... ddl) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:trial-normalized-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
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
    @DisplayName("N1: 후보 seed.sql 원문은 DB로 나가지 않고, 파싱 결과에서 재생성한 파라미터화 INSERT만 실행된다")
    void candidateRawLineIsNeverExecutedOnlyRegeneratedParameterizedInsert() throws Exception {
        List<RecordingConnection> recorders = new ArrayList<>();
        try (Connection real = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)")) {
            Connection recording = RecordingConnection.wrap(real, recorders);
            String rawLine = "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);";
            Path candDir = candidate(rawLine);

            TrialRunner.TrialOutcome outcome =
                    runner(recording).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            RecordingConnection recorder = recorders.get(0);
            assertThat(recorder.rawStatementSql)
                    .as("후보 텍스트가 Statement.execute(String)으로 DB에 나가면 파서가 못 본 SQL이 실행된다")
                    .isEmpty();
            assertThat(recorder.preparedSql)
                    .as("실행 SQL은 카탈로그 식별자 + 물음표 파라미터로 재생성된 정규화 결과여야 한다")
                    .contains("INSERT INTO \"ACCOUNTS\" (\"ID\", \"BALANCE\") VALUES (?, ?)");
            assertThat(count(real, "SELECT COUNT(*) FROM accounts"))
                    .as("정리까지 기존과 동일하게 동작해야 한다(회귀 0)").isZero();
        }
    }

    @Test
    @DisplayName("N1: 파서가 주석으로 버리는 꼬리 텍스트는 재생성 SQL에 남지 않는다(파서가 본 것 == 실행되는 것)")
    void trailingCommentTextIsNotCarriedIntoExecutedSql() throws Exception {
        List<RecordingConnection> recorders = new ArrayList<>();
        try (Connection real = newH2Connection(
                "CREATE TABLE fund_accounts (id VARCHAR(50) PRIMARY KEY, balance_amount BIGINT)")) {
            Connection recording = RecordingConnection.wrap(real, recorders);
            // MySQL/MariaDB 실행형 주석 — JSqlParser는 주석으로 버리지만 MySQL 서버는 실행한다.
            Path candDir = candidate("INSERT INTO fund_accounts (id, balance_amount) "
                    + "VALUES ('seed-x', 1) /*!10000 , ('evil', 999) */;");

            TrialRunner.TrialOutcome outcome =
                    runner(recording).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            RecordingConnection recorder = recorders.get(0);
            assertThat(String.join("\n", recorder.rawStatementSql) + String.join("\n", recorder.preparedSql))
                    .as("실행형 주석 내용('evil')이 DB로 나가는 SQL에 남아서는 안 된다")
                    .doesNotContain("evil");
            assertThat(recorder.preparedSql)
                    .contains("INSERT INTO \"FUND_ACCOUNTS\" (\"ID\", \"BALANCE_AMOUNT\") VALUES (?, ?)");
        }
    }

    /**
     * Phase A 후속(Important 1) 보강: 게이트({@link SeedSqlWhitelist})의 accept/reject만이 아니라
     * {@link TrialRunner}가 <b>실제로 어떤 값을 바인딩하는지</b>를 실행으로 고정한다.
     * {@code closedLiteralValue}의 부호 처리(부호 문자 {@code +}/{@code -}만 허용, {@code -}일 때만
     * negate)에 회귀가 나면 — 예컨대 항상 negate하거나 부호를 통째로 버리면 — 화이트리스트는 여전히
     * accept하므로 {@code SeedSqlWhitelistIT}로는 잡히지 않는다. 삽입된 행은 시험 종료 시 정리되므로,
     * 값 관측은 seed 삽입 이후·정리 이전에 호출되는 {@link TrialRunner.TrialInvoker} 안에서 한다.
     */
    @Test
    @DisplayName("REQ-010(followup): 부호 있는 수치 리터럴은 부호를 보존해 바인딩된다(-5는 -5, +7은 7)")
    void signedNumericLiteralsAreBoundWithSignPreserved() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT, credit BIGINT)")) {
            List<Long> observedBalance = new ArrayList<>();
            List<Long> observedCredit = new ArrayList<>();
            TrialRunner.TrialInvoker probingInvoker = (endpoint, body) -> {
                // seed INSERT 직후·정리 DELETE 이전 시점 — 실제로 DB에 들어간 값을 관측한다.
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT balance, credit FROM accounts WHERE id = 'acc-n'")) {
                    while (rs.next()) {
                        observedBalance.add(rs.getLong(1));
                        observedCredit.add(rs.getLong(2));
                    }
                }
                return new InvocationOutcome(200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            };
            Path candDir = candidate(
                    "INSERT INTO accounts (id, balance, credit) VALUES ('acc-n', -5, +7);");

            TrialRunner.TrialOutcome outcome = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new SilentSutHandle(), probingInvoker)
                    .runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            assertThat(observedBalance)
                    .as("음수 부호 리터럴 -5는 negate되어 -5로 삽입돼야 한다(부호 유실 회귀 차단)")
                    .containsExactly(-5L);
            assertThat(observedCredit)
                    .as("양수 부호 리터럴 +7은 그대로 7로 삽입돼야 한다(항상 negate 회귀 차단)")
                    .containsExactly(7L);
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts"))
                    .as("시험은 probe이므로 삽입 행은 정리되어 잔여 0이어야 한다").isZero();
        }
    }

    @Test
    @DisplayName("N1(회귀 0): NULL 리터럴이 섞인 후보도 파라미터 바인딩으로 삽입·정리된다")
    void nullLiteralIsBoundAsParameter() throws Exception {
        try (Connection connection = newH2Connection(
                "CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT, memo VARCHAR(50))")) {
            Path candDir = candidate("INSERT INTO accounts (id, balance, memo) VALUES ('acc-n', 5, NULL);");

            TrialRunner.TrialOutcome outcome =
                    runner(connection).runCandidate(ENDPOINT, candDir, List.of(), EMPTY_REPORT);

            assertThat(outcome.promoted()).isTrue();
            assertThat(count(connection, "SELECT COUNT(*) FROM accounts")).isZero();
        }
    }
}
