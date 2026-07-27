package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.provenance.TripleStore;
import io.graphrag.model.Json;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-013/016 CLI(T2) black-box E2E — {@code trial} 서브커맨드가 Testcontainers Postgres + 최소
 * fake HTTP SUT(com.sun.net.httpserver)를 대상으로 REQ-013 시퀀스(happy 시드 정리→후보 seed.sql
 * INSERT→invoke→판정→승격)와 REQ-016 예산 소진(전부 실패→failed/+최종 다이제스트+exit 3)을
 * 검증한다. {@link ReproVerifierRealDropIntegrationTest}와 동일한 최소-의존 패턴(실 DB+실 HTTP
 * 서버, 무거운 order-service 픽스처 불필요)을 재사용한다.
 *
 * <p>fake SUT({@code POST /api/transfers})는 accounts.balance를 조회해 amount 이상이면 200, 아니면
 * 422를 반환한다 — REQ-014의 "balance 부족" 시나리오와 동일한 판정 규칙.
 */
@Tag("docker")
@Testcontainers
class TrialCliE2E {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    private static HttpServer httpServer;
    private static int httpPort;
    private static Connection sutConnection;   // fake SUT 핸들러 전용(테스트 CLI 호출과 별개 커넥션)

    @TempDir
    Path tempDir;

    @BeforeAll
    static void up() throws Exception {
        sutConnection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement st = sutConnection.createStatement()) {
            st.execute("CREATE TABLE accounts (id VARCHAR(50) PRIMARY KEY, balance BIGINT NOT NULL)");
        }
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpPort = httpServer.getAddress().getPort();
        httpServer.createContext("/api/transfers", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectNode json = (ObjectNode) Json.mapper().readTree(requestBody);
            String accountId = json.get("accountId").asText();
            long amount = json.get("amount").asLong();
            int status;
            try {
                try (PreparedStatement ps = sutConnection.prepareStatement(
                        "SELECT balance FROM accounts WHERE id = ?")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            status = 404;
                        } else {
                            status = rs.getLong(1) >= amount ? 200 : 422;
                        }
                    }
                }
            } catch (java.sql.SQLException e) {
                status = 500;
            }
            byte[] resp = ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (sutConnection != null && !sutConnection.isClosed()) {
            sutConnection.close();
        }
    }

    /** endpointDir/cand-NN + base/cand-NN에 동일 내용의 body.json/seed.sql/stubs.json을 쓴다. */
    private static Path writeCandidate(Path endpointDir, String candName, String bodyJson, String seedSql)
            throws Exception {
        Path candDir = Files.createDirectories(endpointDir.resolve(candName));
        Path baseDir = Files.createDirectories(endpointDir.resolve("base").resolve(candName));
        for (Path dir : List.of(candDir, baseDir)) {
            Files.writeString(dir.resolve("body.json"), bodyJson);
            Files.writeString(dir.resolve("seed.sql"), seedSql);
            Files.writeString(dir.resolve("stubs.json"), "{}");
        }
        return candDir;
    }

    private Path writeHappySeeds(RequiredSeed... seeds) throws Exception {
        Path file = tempDir.resolve("happy-seeds.json");
        Files.writeString(file, Json.mapper().writeValueAsString(List.of(seeds)));
        return file;
    }

    /**
     * T1 검증 게이트(C4 fix)가 요구하는 provenance 리포트를 저장 레이아웃 규약 위치에 쓴다 —
     * {@code accounts}를 DB_READ 테이블로 선언해 seed.sql 화이트리스트(REQ-010)를 통과시킨다.
     */
    private static void writeProvenanceReport(Path endpointDir) throws Exception {
        var guard = new io.graphrag.builder.provenance.ProvenanceReport.GuardFact(
                "Fixture.java:1", "EXISTS",
                List.of(new io.graphrag.builder.provenance.ProvenanceReport.ValueRef(
                        io.graphrag.builder.provenance.ProvenanceReport.Origin.DB_READ,
                        null, "accounts", "id", null, null, "String", null, null)));
        var report = new io.graphrag.builder.provenance.ProvenanceReport(
                "post-api-transfers", List.of(guard), List.of(), List.of());
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(report));
    }

    private Map<String, String> baseOptions(Path tripleStore, Path happySeedsFile) {
        return Map.ofEntries(
                Map.entry("--triple-store", tripleStore.toString()),
                Map.entry("--endpoint", "post-api-transfers"),
                Map.entry("--http-method", "POST"),
                Map.entry("--path", "/api/transfers"),
                Map.entry("--sut-base-url", "http://localhost:" + httpPort),
                Map.entry("--jdbc-url", PG.getJdbcUrl()),
                Map.entry("--db-user", PG.getUsername()),
                Map.entry("--db-password", PG.getPassword()),
                Map.entry("--db-type", "postgres"),
                Map.entry("--happy-seeds", happySeedsFile.toString()),
                Map.entry("--trial-budget", "8"));
    }

    @Test
    @DisplayName("REQ-013: 유효 후보는 시퀀스대로 적용되어(이중 INSERT 없음) promoted/로 이동하고 exit 0을 반환한다")
    void req013_validCandidatePromotedWithoutDoubleInsert() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        // happy(현행) 시드: acc-1 balance=100 — 이 candidate의 시도(amount=500) 기준으로는 잔액 부족.
        // reset(①)이 제대로 동작해야 candidate의 seed.sql(②, 같은 PK acc-1)이 PK 충돌 없이 들어간다.
        Path happySeeds = writeHappySeeds(
                new RequiredSeed("s1", "happy-path", "accounts", List.of("id", "balance"), List.of("acc-1", "100")));
        Path candDir = writeCandidate(endpointDir, "cand-01",
                "{\"accountId\":\"acc-1\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");

        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode).as("2xx 판정이면 exit 0이어야 한다").isEqualTo(0);
        assertThat(candDir).doesNotExist();
        assertThat(endpointDir.resolve("promoted").resolve("cand-01"))
                .as("성공 후보는 promoted/cand-01로 이동해야 한다")
                .isDirectory();
        assertThat(endpointDir.resolve("failed")).doesNotExist();

        try (Statement st = sutConnection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts WHERE id = 'acc-1'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("trial은 probe이므로 후보가 삽입한 행은 정리되어 중복/잔여 없이 0이어야 한다 "
                            + "(reset이 실패했다면 애초 PK 충돌로 후보 INSERT 자체가 예외를 던졌을 것)")
                    .isEqualTo(0);
        }
    }

    @Test
    @DisplayName("REQ-016: 전부 실패하는 후보들은 budget 소진 후 failed/ + 최종 digest 보고서를 남기고 exit 3을 반환한다")
    void req016_allFailingCandidatesExhaustBudgetAndReportFinalDigest() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        Path happySeeds = writeHappySeeds();   // happy 시드 없음(단순화) — reset은 no-op

        Path cand01 = writeCandidate(endpointDir, "cand-01",
                "{\"accountId\":\"acc-1\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 10);");   // 잔액 부족 -> 422
        Path cand02 = writeCandidate(endpointDir, "cand-02",
                "{\"accountId\":\"acc-2\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-2', 20);");   // 잔액 부족 -> 422

        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode).as("전부 실패하면 exit 3(비-promoted)이어야 한다").isEqualTo(3);
        assertThat(cand01).doesNotExist();
        assertThat(cand02).doesNotExist();
        assertThat(endpointDir.resolve("promoted")).doesNotExist();
        assertThat(endpointDir.resolve("failed").resolve("cand-01"))
                .as("실패 후보는 failed/cand-01로 이동해야 한다").isDirectory();
        assertThat(endpointDir.resolve("failed").resolve("cand-02"))
                .as("실패 후보는 failed/cand-02로 이동해야 한다").isDirectory();

        Path finalDigest = endpointDir.resolve("failed").resolve("digest-final.json");
        assertThat(finalDigest).as("예산/후보 소진 시 최종 다이제스트 보고서가 남아야 한다").exists();
        var digests = Json.mapper().readTree(finalDigest.toFile());
        assertThat(digests.isArray()).isTrue();
        assertThat(digests).hasSize(2);
        digests.forEach(d -> assertThat(d.get("status").asInt()).isEqualTo(422));

        // TripleStore가 보는 관점에서도 대기 후보가 더는 없어야 한다(전부 failed로 소비됨).
        assertThat(new TripleStore(tripleStore).candidates("post-api-transfers")).isEmpty();
    }

    /**
     * REQ-016 명목 시나리오: 후보 수(3) &gt; budget(2) — {@code attempts >= budget} 분기를 실제로
     * 타야 한다(이전 테스트는 후보 소진만 검증해 이 분기를 커버하지 못했다). 미시도 후보는 원위치
     * 보존(재시도 대상)이 계약이다({@code BuilderCli.runTrial} Javadoc "예산 소진 semantics" 참고) —
     * promoted/failed 어느 쪽으로도 이동하지 않고 digest-final에도 포함되지 않는다.
     */
    @Test
    @DisplayName("REQ-016: budget < 후보 수이면 예산만큼만 시도하고, 미시도 후보는 원위치 보존·digest-final 미포함으로 exit 3을 반환한다")
    void req016_budgetSmallerThanCandidateCountLeavesUntriedCandidateInPlace() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        Path happySeeds = writeHappySeeds();

        Path cand01 = writeCandidate(endpointDir, "cand-01",
                "{\"accountId\":\"acc-1\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 10);");   // 잔액 부족 -> 422
        Path cand02 = writeCandidate(endpointDir, "cand-02",
                "{\"accountId\":\"acc-2\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-2', 20);");   // 잔액 부족 -> 422
        Path cand03 = writeCandidate(endpointDir, "cand-03",
                "{\"accountId\":\"acc-3\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-3', 30);");   // budget 밖 — 시도조차 안 됨

        java.util.Map<String, String> options = new java.util.HashMap<>(baseOptions(tripleStore, happySeeds));
        options.put("--trial-budget", "2");   // 후보 3개 중 2개만 시도 가능

        int exitCode = BuilderCli.runTrial(options);

        assertThat(exitCode).as("시도한 후보가 전부 실패하면 exit 3이어야 한다").isEqualTo(3);
        assertThat(endpointDir.resolve("promoted")).doesNotExist();
        assertThat(endpointDir.resolve("failed").resolve("cand-01")).as("시도①은 failed로 이동").isDirectory();
        assertThat(endpointDir.resolve("failed").resolve("cand-02")).as("시도②는 failed로 이동").isDirectory();
        assertThat(endpointDir.resolve("failed").resolve("cand-03"))
                .as("budget 밖(3번째)은 failed로 이동하면 안 된다").doesNotExist();
        assertThat(cand03)
                .as("미시도 후보는 원위치(top-level cand-03)에 그대로 남아 재시도 가능해야 한다")
                .isDirectory();
        assertThat(new TripleStore(tripleStore).candidates("post-api-transfers"))
                .as("TripleStore 관점에서도 미시도 후보(cand-03)는 여전히 대기 목록에 있어야 한다")
                .extracting(p -> p.getFileName().toString())
                .containsExactly("cand-03");

        Path finalDigest = endpointDir.resolve("failed").resolve("digest-final.json");
        var digests = Json.mapper().readTree(finalDigest.toFile());
        assertThat(digests).as("digest-final에는 실제로 시도한 2건만 기록되어야 한다(미시도 후보 제외)").hasSize(2);
    }

    /**
     * REQ-013 예외 격리(구현 리뷰 Important 3): 후보의 seed.sql이 다중 INSERT 중 후반 문장에서 실패
     * (PK 중복)하면 — ① 그 후보가 이미 삽입한 앞쪽 행은 finally에서 정리되고(누수 없음), ②
     * {@code runTrial} 루프는 그 후보만 failed 처리한 뒤 중단 없이 다음 후보로 진행해야 한다.
     */
    @Test
    @DisplayName("REQ-013: 다중 INSERT 중 두 번째가 실패하는 후보는 첫 행이 정리되고 failed로 격리되며, 루프는 다음 후보로 계속된다")
    void req013_midSeedExceptionIsolatesCandidateAndCleansUpPartialInsert() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        Path happySeeds = writeHappySeeds();

        // cand-01: 두 번째 INSERT가 같은 PK('acc-x')를 재사용해 제약 위반으로 예외를 던진다.
        Path cand01 = writeCandidate(endpointDir, "cand-01",
                "{\"accountId\":\"acc-x\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-x', 10);\n"
                        + "INSERT INTO accounts (id, balance) VALUES ('acc-x', 20);");
        // cand-02: 정상 성공 후보 — 루프가 cand-01의 예외 이후에도 이어져야 도달한다.
        Path cand02 = writeCandidate(endpointDir, "cand-02",
                "{\"accountId\":\"acc-2\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-2', 600);");

        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode).as("cand-01 예외 이후에도 cand-02가 성공하면 exit 0이어야 한다").isEqualTo(0);
        assertThat(cand01).doesNotExist();
        assertThat(endpointDir.resolve("failed").resolve("cand-01"))
                .as("mid-seed 예외 후보는 failed로 격리되어야 한다(루프 중단 아님)").isDirectory();
        assertThat(endpointDir.resolve("promoted").resolve("cand-02"))
                .as("cand-01 예외에도 불구하고 루프가 계속돼 cand-02가 promoted로 이동해야 한다").isDirectory();

        try (Statement st = sutConnection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts WHERE id = 'acc-x'")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("두 번째 INSERT가 실패해도 첫 번째로 성공한 행('acc-x')은 finally에서 정리되어 "
                            + "잔여가 없어야 한다(부분 삽입 누수 없음)")
                    .isEqualTo(0);
        }
    }

    /**
     * C4 리뷰 Critical 3(a) 회귀: 독립 {@code trial} CLI가 T1 검증 게이트를 실제로 호출한다. 마커가
     * 아닌 값을 바꾼 후보(REQ-009 위반)는 <b>DB를 전혀 건드리지 않고</b> {@code T1_REJECTED}로
     * {@code failed/}에 격리돼야 한다 — 고치기 전에는 검증 없이 그대로 시험돼 승격까지 갔다.
     */
    @Test
    @DisplayName("C4-3(a): T1 게이트에서 거부된 후보(비-마커 값 변경)는 시험되지 않고 T1_REJECTED로 failed/에 격리된다")
    void t1GateRejectsNonMarkerChangeInStandaloneCli() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        Path happySeeds = writeHappySeeds();

        // base는 balance=10(잔액 부족 → 422), candidate는 마커가 아닌 그 값을 600으로 바꿔(REQ-009 위반)
        // 200을 노린다 — T1이 배선돼 있다면 시험 자체가 일어나지 않아야 한다.
        Path candDir = Files.createDirectories(endpointDir.resolve("cand-01"));
        Path baseDir = Files.createDirectories(endpointDir.resolve("base").resolve("cand-01"));
        String body = "{\"accountId\":\"acc-1\",\"amount\":500}";
        Files.writeString(baseDir.resolve("body.json"), body);
        Files.writeString(baseDir.resolve("seed.sql"),
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 10);");
        Files.writeString(baseDir.resolve("stubs.json"), "{}");
        Files.writeString(candDir.resolve("body.json"), body);
        Files.writeString(candDir.resolve("seed.sql"),
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        Files.writeString(candDir.resolve("stubs.json"), "{}");

        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode).as("T1 거부만 남았으면 승격 없이 exit 3이어야 한다").isEqualTo(3);
        assertThat(endpointDir.resolve("promoted")).as("T1 거부 후보가 승격되면 안 된다").doesNotExist();
        assertThat(endpointDir.resolve("failed").resolve("cand-01")).isDirectory();
        var digests = Json.mapper().readTree(
                endpointDir.resolve("failed").resolve("digest-final.json").toFile());
        assertThat(digests).hasSize(1);
        assertThat(digests.get(0).get("outcomeKind").asText())
                .as("T1에서 거부된 후보는 trial 판정이 아니라 T1_REJECTED로 기록되어야 한다")
                .isEqualTo("T1_REJECTED");
    }

    /**
     * C4 리뷰 Critical 3(a) 회귀: provenance 리포트가 없으면 seed.sql 화이트리스트(REQ-010)의 허용
     * 테이블 집합을 결정할 수 없으므로, 후보를 시험하지 않고 즉시 실패한다(fail-closed).
     */
    @Test
    @DisplayName("C4-3(a): provenance 리포트가 없으면 후보를 시험하지 않고 즉시 실패한다(fail-closed)")
    void missingProvenanceReportFailsClosed() throws Exception {
        Path tripleStore = tempDir.resolve("triples");
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        Path happySeeds = writeHappySeeds();
        writeCandidate(endpointDir, "cand-01", "{\"accountId\":\"acc-1\",\"amount\":500}",
                "INSERT INTO accounts (id, balance) VALUES ('acc-1', 600);");
        // provenance-report.json을 의도적으로 쓰지 않는다.

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> BuilderCli.runTrial(baseOptions(tripleStore, happySeeds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance-report.json");
        assertThat(endpointDir.resolve("promoted")).doesNotExist();
    }

    /**
     * N3 회귀(리뷰 Important): <b>문서화된 파이프라인 순서 그대로</b> — provenance(임의 {@code --out}
     * 경로) → {@code synthesize-triple} → {@code trial}({@code --provenance-report} 없이) — 을 돌렸을 때
     * 첫 {@code trial}이 죽지 않아야 한다.
     *
     * <p>고치기 전의 결함: {@code synthesize-triple}이 입력 리포트를 저장 레이아웃 규약 위치
     * ({@code <triple-store>/<endpointId>/provenance-report.json})로 복사하지 않아, 문서가 안내하는
     * 순서대로 실행하면 규약 위치에 파일이 없어 {@code trial}이 {@code IllegalArgumentException}으로
     * 즉시 실패했다(스킬 워크플로 파손).
     */
    @Test
    @DisplayName("N3: provenance → synthesize-triple → trial(플래그 없이) 문서 순서가 실제로 동작한다")
    void documentedPipelineOrderWorksWithoutExplicitReportFlag() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
        }
        // ① provenance 산출물은 triple-store 밖의 임의 경로에 있다(provenance CLI의 --out 계약).
        Path analysisDir = Files.createDirectories(tempDir.resolve("analysis"));
        Path externalReport = analysisDir.resolve("provenance-report.json");
        writeProvenanceReport(analysisDir);
        Path tripleStore = tempDir.resolve("pipeline-triples");

        // ② synthesize-triple — 후보와 함께 규약 위치로 리포트가 복사되어야 한다.
        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", externalReport.toString(),
                "--triple-store", tripleStore.toString()
        });

        Path canonicalReport = tripleStore.resolve("post-api-transfers").resolve("provenance-report.json");
        assertThat(canonicalReport)
                .as("synthesize-triple은 입력 리포트를 trial이 읽는 규약 위치로 복사해야 한다")
                .exists();
        assertThat(Files.readString(canonicalReport)).isEqualTo(Files.readString(externalReport));

        // ③ trial — --provenance-report 없이도 규약 위치를 찾아 후보를 실제로 시험해야 한다.
        Path happySeeds = writeHappySeeds();
        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode)
                .as("리포트를 찾았으므로 fail-closed 예외 없이 정상 종료 코드(0 또는 3)여야 한다")
                .isIn(0, 3);
        if (exitCode == 3) {
            assertThat(tripleStore.resolve("post-api-transfers").resolve("failed")
                    .resolve("digest-final.json"))
                    .as("후보가 실제로 시험(또는 T1 판정)됐다는 증거")
                    .exists();
        }
    }
}
