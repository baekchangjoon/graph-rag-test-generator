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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 attach 안전 게이트 — REQ-023(seed 이중 opt-in)·REQ-024(역-DELETE 실패 시 승격 차단) 회귀.
 * {@link TrialDigestIT}와 동일하게 H2 in-memory + fake {@link TrialRunner.TrialInvoker}/
 * {@link SutHandle}로 실 HTTP/SUT 없이 {@link TrialRunner}의 attach 안전 게이트 분기만 검증한다.
 */
class AttachSeedGateIT {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    private static final Endpoint ENDPOINT =
            new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);

    private Connection newH2Connection() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:attach-seed-gate-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)");
            // REQ-024: transactions는 accounts를 참조하는 FK 자식 테이블 — invoke 도중 SUT가(실제로는
            // 여기서는 fakeInvoker가 대신) 삽입하는 행을 흉내내, TrialRunner가 추적하지 못한 그 행이
            // 남아 있으면 accounts 행의 역-DELETE가 FK 위반으로 실패하는 상황을 재현한다.
            st.execute("CREATE TABLE transactions (id VARCHAR(50) PRIMARY KEY, account_id VARCHAR(50), "
                    + "FOREIGN KEY (account_id) REFERENCES accounts(id))");
        }
        return connection;
    }

    private Path candidate() throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-01-" + DB_SEQ.incrementAndGet()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 500);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        return candDir;
    }

    private static class FixedLogSutHandle implements SutHandle {
        private final String log;

        FixedLogSutHandle(String log) {
            this.log = log;
        }

        @Override public String baseUri() { return "http://fake"; }
        @Override public long logOffset() { return 0; }
        @Override public String readLog() { return log; }
        @Override public String readLogFrom(long offset) { return log; }
        @Override public String readLogRange(long start, long end) { return log; }
        @Override public void stop() { }
    }

    private static int accountRowCount(Connection connection) throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---- REQ-023: 이중 opt-in 0/1/2개 → 미적용·미적용·적용 ----

    @Test
    @DisplayName("REQ-023: attach에서 두 플래그 모두 없으면 seed가 전혀 적용되지 않고(DB 부작용 0) 사유가 두 플래그를 모두 지목한다")
    void req023_zeroFlagsSkipsSeedEntirelyWithBothMissingInReason() throws Exception {
        Path candDir = candidate();
        AtomicBoolean invokerCalled = new AtomicBoolean(false);
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> {
                invokerCalled.set(true);
                throw new AssertionError("seed gate가 닫혀 있으면 invoke까지 도달하면 안 된다");
            };
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker,
                    /* attachMode */ true, /* allowSeedFlag */ false, /* confirmNonProductionFlag */ false);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), null);

            assertThat(outcome.promoted()).isFalse();
            assertThat(invokerCalled).isFalse();
            assertThat(accountRowCount(connection))
                    .as("seed gate가 닫혀 있으면 candidate seed.sql이 전혀 실행되지 않아야 한다")
                    .isEqualTo(0);
            assertThat(outcome.digest().outcomeKind()).isEqualTo("ATTACH_SEED_GATE_CLOSED");
            assertThat(outcome.digest().logExcerpt())
                    .contains("--attach-allow-seed")
                    .contains("--confirm-non-production");
        }
    }

    @Test
    @DisplayName("REQ-023: --attach-allow-seed만 있으면(1개) seed 미적용 + 사유는 --confirm-non-production 누락만 지목")
    void req023_oneFlagAllowSeedOnlySkipsSeedAndReportsMissingConfirm() throws Exception {
        Path candDir = candidate();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> {
                throw new AssertionError("seed gate가 닫혀 있으면 invoke까지 도달하면 안 된다");
            };
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker,
                    true, /* allowSeedFlag */ true, /* confirmNonProductionFlag */ false);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), null);

            assertThat(outcome.promoted()).isFalse();
            assertThat(accountRowCount(connection)).isEqualTo(0);
            assertThat(outcome.digest().outcomeKind()).isEqualTo("ATTACH_SEED_GATE_CLOSED");
            assertThat(outcome.digest().logExcerpt())
                    .as("allowSeed는 이미 충족됐으므로 confirm-non-production만 누락으로 지목돼야 한다")
                    .doesNotContain("--attach-allow-seed")
                    .contains("--confirm-non-production");
        }
    }

    @Test
    @DisplayName("REQ-023: --confirm-non-production만 있으면(1개) seed 미적용 + 사유는 --attach-allow-seed 누락만 지목")
    void req023_oneFlagConfirmOnlySkipsSeedAndReportsMissingAllowSeed() throws Exception {
        Path candDir = candidate();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> {
                throw new AssertionError("seed gate가 닫혀 있으면 invoke까지 도달하면 안 된다");
            };
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker,
                    true, /* allowSeedFlag */ false, /* confirmNonProductionFlag */ true);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), null);

            assertThat(outcome.promoted()).isFalse();
            assertThat(accountRowCount(connection)).isEqualTo(0);
            assertThat(outcome.digest().logExcerpt())
                    .contains("--attach-allow-seed")
                    .doesNotContain("--confirm-non-production");
        }
    }

    @Test
    @DisplayName("REQ-023: 두 플래그 모두 있으면(2개) attach에서도 seed가 정상 적용되고 후보가 승격된다")
    void req023_bothFlagsAllowsSeedApplicationAndPromotion() throws Exception {
        Path candDir = candidate();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker,
                    true, /* allowSeedFlag */ true, /* confirmNonProductionFlag */ true);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(),
                    new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of()));

            assertThat(outcome.promoted()).isTrue();
            assertThat(accountRowCount(connection))
                    .as("trial 종료 후에는 후보 seed도 항상 정리되어(probe 원칙) 잔여가 없어야 한다")
                    .isEqualTo(0);
        }
    }

    // ---- REQ-024: 역-DELETE 실패(FK 자식 행) → 승격 차단 + 잔존 (table, pk) 리포트 ----

    @Test
    @DisplayName("REQ-024: attach에서 invoke가 만든 FK 자식 행 때문에 역-DELETE가 실패하면 원래 판정과 무관하게 승격이 차단되고 잔존 행이 보고된다")
    void req024_reverseDeleteFailureBlocksPromotionAndReportsRemainingRow() throws Exception {
        Path candDir = candidate();
        try (Connection connection = newH2Connection()) {
            // fakeInvoker가 실제 SUT의 부작용을 흉내낸다: TrialRunner가 추적하지 않는 transactions
            // 자식 행을 삽입한 뒤 성공(200)을 반환한다 — 겉보기 판정은 SUCCESS다.
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> {
                try (Statement st = connection.createStatement()) {
                    st.execute("INSERT INTO transactions (id, account_id) VALUES ('txn-1', 'acc-1')");
                }
                return new InvocationOutcome(200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            };
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker,
                    true, true, true);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(),
                    new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of()));

            assertThat(outcome.promoted())
                    .as("invoke 자체는 SUCCESS였지만 cleanup 실패로 승격이 차단되어야 한다")
                    .isFalse();
            assertThat(outcome.digest()).isNotNull();
            assertThat(outcome.digest().outcomeKind()).isEqualTo("ATTACH_CLEANUP_BLOCKED");
            assertThat(outcome.digest().attachRemainingRows())
                    // C4 fix 이후 식별자는 후보 텍스트가 아니라 DB 카탈로그 표기를 쓴다(H2는 대문자로
                    // 보고) — 대소문자 무시로 대조한다.
                    .as("잔존 (table, pk) 리포트에 정리 실패한 accounts 행이 담겨야 한다")
                    .anyMatch(row -> {
                        String lower = row.toLowerCase(java.util.Locale.ROOT);
                        return lower.contains("accounts") && lower.contains("id") && lower.contains("acc-1");
                    });
            assertThat(accountRowCount(connection))
                    .as("실제로 정리에 실패한 행은 DB에 그대로 남아 있어야 한다(리포트와 실제 상태 일치)")
                    .isEqualTo(1);

            // 정리(teardown): 이 테스트가 남긴 FK 자식 행을 먼저 지운 뒤 부모 행을 정리한다(H2 in-memory,
            // connection 자체가 try-with-resources로 닫히면 DB_CLOSE_DELAY=-1이라도 세션 종료로 소멸하지만
            // 명시적으로 되돌려 다른 검증에 영향 없게 한다).
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM transactions WHERE id = 'txn-1'");
                st.execute("DELETE FROM accounts WHERE id = 'acc-1'");
            }
        }
    }

    /** REQ-013/REQ-023 비-attach 회귀: attachMode=false(6-arg 생성자)면 이중 opt-in 게이트가 전혀 발동하지 않는다. */
    @Test
    @DisplayName("회귀: attachMode=false(비-attach, 6-arg 생성자)면 플래그 없이도 seed가 정상 적용된다")
    void nonAttachModeRegressionSeedAlwaysAppliedRegardlessOfFlags() throws Exception {
        Path candDir = candidate();
        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(),
                    new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of()));

            assertThat(outcome.promoted()).isTrue();
        }
    }
}
