package io.graphrag.builder.provenance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.explore.InvocationOutcome;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.Json;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-014: {@link TrialRunner} 실패 경로가 산출하는 {@link FailureDigest}의 가드 역매핑(mappedGuard)을
 * 3케이스(스택-매칭 성공+toolSuggestion, literal 폴백 매칭, 매핑 불가)로 검증한다. fake
 * {@link TrialRunner.TrialInvoker}(응답 status/body를 케이스별로 고정)와 fake {@link SutHandle}(로그
 * 텍스트를 케이스별로 고정)로 실 HTTP/SUT 없이 판정 이후 흐름만 확인하고, seed 처리(①②)는 H2 in-memory로
 * 실행한다(Docker 불필요 — 빠른 unit-레벨 integration).
 */
class TrialDigestIT {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    @TempDir
    Path tempDir;

    private static final Endpoint ENDPOINT =
            new Endpoint("post-api-transfers", "POST", "/api/transfers", "T", "t", List.of(), false);

    private Connection newH2Connection() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:trial-digest-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT)");
        }
        return connection;
    }

    private Path candidate(String seedSql) throws Exception {
        Path candDir = Files.createDirectories(tempDir.resolve("cand-01-" + DB_SEQ.get()));
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("amount", 500);
        Files.writeString(candDir.resolve("body.json"), body.toString());
        Files.writeString(candDir.resolve("seed.sql"), seedSql);
        Files.writeString(candDir.resolve("stubs.json"), "{}");
        return candDir;
    }

    /** balance < amount 가드(NUMERIC "<") — DB_READ(accounts.balance) vs INPUT(amount). */
    private static GuardFact balanceGuard() {
        return new GuardFact("TransferService.java:44", "<", List.of(
                new ValueRef(Origin.DB_READ, null, "accounts", "balance", null, null, "long", "balance", null),
                new ValueRef(Origin.INPUT, "amount", null, null, null, null, "long", "amount", null)));
    }

    /** 계좌 존재 가드(EXISTS) — literal("ACC-404")로 폴백 매칭 근거를 제공. */
    private static GuardFact existsGuard() {
        return new GuardFact("AccountRepo.java:10", "EXISTS", List.of(
                new ValueRef(Origin.DB_READ, null, "accounts", "id", null, null, "String", "accountId", "ACC-404")));
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

    @Test
    @DisplayName("REQ-014: 스택 프레임이 가드 위치와 정확 대조되면 mappedGuard + NUMERIC 경계 toolSuggestion 산출")
    void req014_stackFrameMapsToGuardWithBoundarySuggestion() throws Exception {
        Path candDir = candidate("INSERT INTO accounts (id, balance) VALUES ('acc-1', 100);");
        String log = "2026-07-27 ERROR InsufficientBalanceException\n"
                + "\tat com.example.order.TransferService.checkBalance(TransferService.java:44)\n"
                + "\tat com.example.order.TransferController.transfer(TransferController.java:20)\n";
        ProvenanceReport report = new ProvenanceReport(
                "post-api-transfers", List.of(balanceGuard(), existsGuard()), List.of(), List.of());

        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    422, Json.mapper().readTree("{\"error\":\"insufficient balance\"}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(log), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), report);

            assertThat(outcome.promoted()).isFalse();
            FailureDigest digest = outcome.digest();
            assertThat(digest).isNotNull();
            assertThat(digest.mappedGuard())
                    .as("스택 프레임(File:line)이 guard.at()과 정확 대조되어야 한다")
                    .isEqualTo("<@TransferService.java:44");
            assertThat(digest.toolSuggestion())
                    .as("NUMERIC(\"<\") 가드는 경계 만족 seed.sql 패치를 제안해야 한다")
                    .isNotNull();
            assertThat(digest.toolSuggestion().at("/seed.sql/column").asText()).isEqualTo("balance");
            assertThat(digest.toolSuggestion().at("/seed.sql/value").asLong())
                    .as("balance < amount(500) 위반 → balance를 500으로 올려야 경계(>=)를 만족한다")
                    .isEqualTo(500L);
        }
    }

    @Test
    @DisplayName("REQ-014: 스택 매칭 실패 시 응답 텍스트의 가드 literal 부분일치로 mappedGuard 폴백 매칭(비-NUMERIC은 toolSuggestion 없음)")
    void req014_literalFallbackMapsToGuardWithoutSuggestion() throws Exception {
        Path candDir = candidate("INSERT INTO accounts (id, balance) VALUES ('acc-1', 100);");
        // 가드 위치와 무관한 스택(매칭 실패) — 응답 바디에 existsGuard의 literal("ACC-404")만 등장.
        String log = "2026-07-27 ERROR\n\tat some.other.Unrelated.method(Unrelated.java:1)\n";
        ProvenanceReport report = new ProvenanceReport(
                "post-api-transfers", List.of(balanceGuard(), existsGuard()), List.of(), List.of());

        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    404, Json.mapper().readTree("{\"error\":\"account ACC-404 not found\"}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(log), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), report);

            assertThat(outcome.promoted()).isFalse();
            FailureDigest digest = outcome.digest();
            assertThat(digest.mappedGuard())
                    .as("응답의 literal(ACC-404) 부분일치로 existsGuard가 폴백 채택돼야 한다")
                    .isEqualTo("EXISTS@AccountRepo.java:10");
            assertThat(digest.toolSuggestion())
                    .as("EXISTS는 NUMERIC 비교가 아니므로 toolSuggestion이 없어야 한다")
                    .isNull();
        }
    }

    @Test
    @DisplayName("REQ-014: 스택도 literal도 매핑 불가하면 mappedGuard=null이고 원시 로그 구간은 보존된다")
    void req014_unmappableFailureYieldsNullMappedGuardWithRawLog() throws Exception {
        Path candDir = candidate("INSERT INTO accounts (id, balance) VALUES ('acc-1', 100);");
        String log = "2026-07-27 ERROR unrelated failure, no stack frame here\n";
        ProvenanceReport report = new ProvenanceReport(
                "post-api-transfers", List.of(balanceGuard(), existsGuard()), List.of(), List.of());

        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    500, Json.mapper().readTree("{\"error\":\"unexpected\"}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(log), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), report);

            assertThat(outcome.promoted()).isFalse();
            FailureDigest digest = outcome.digest();
            assertThat(digest.mappedGuard()).isNull();
            assertThat(digest.toolSuggestion()).isNull();
            assertThat(digest.logExcerpt())
                    .as("매핑 불가여도 원시 로그 구간은 그대로 보존되어야 한다")
                    .isEqualTo(log);
            assertThat(digest.status()).isEqualTo(500);
        }
    }

    @Test
    @DisplayName("REQ-013 성공 판정: 2xx면 promoted=true이고 digest는 null")
    void successfulInvocationYieldsPromotedWithNullDigest() throws Exception {
        Path candDir = candidate("INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        ProvenanceReport report = new ProvenanceReport("post-api-transfers", List.of(), List.of(), List.of());

        try (Connection connection = newH2Connection()) {
            TrialRunner.TrialInvoker fakeInvoker = (endpoint, body) -> new InvocationOutcome(
                    200, Json.mapper().readTree("{\"ok\":true}"), Set.of(), 0, 0);
            TrialRunner runner = new TrialRunner(connection, DbConfig.Type.POSTGRES, null,
                    new StatusOnlyClassifier(), new FixedLogSutHandle(""), fakeInvoker);

            TrialRunner.TrialOutcome outcome = runner.runCandidate(ENDPOINT, candDir, List.of(), report);

            assertThat(outcome.promoted()).isTrue();
            assertThat(outcome.status()).isEqualTo(200);
            assertThat(outcome.digest()).isNull();
        }
    }
}
