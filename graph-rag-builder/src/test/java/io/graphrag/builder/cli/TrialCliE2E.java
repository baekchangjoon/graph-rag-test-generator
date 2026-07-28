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
        // ---- JWT 보호 SUT 시뮬레이션(REQ-013 인증 배선 회귀) ----
        // samples/order-service의 SecurityConfig과 동일한 형태: /api/auth/login만 공개이고, 나머지는
        // Authorization 헤더가 없으면 내용과 무관하게 403.
        httpServer.createContext("/api/auth/login", exchange -> {
            byte[] resp = ("{\"" + AUTH_TOKEN_FIELD + "\":\"" + AUTH_TOKEN + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        httpServer.createContext(SECURED_PATH, exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            int status;
            if (!("Bearer " + AUTH_TOKEN).equals(authorization)) {
                status = 403;
            } else {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                ObjectNode json = (ObjectNode) Json.mapper().readTree(requestBody);
                String accountId = json.get("accountId").asText();
                long amount = json.get("amount").asLong();
                try (PreparedStatement ps = sutConnection.prepareStatement(
                        "SELECT balance FROM accounts WHERE id = ?")) {
                    ps.setString(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        status = !rs.next() ? 404 : (rs.getLong(1) >= amount ? 200 : 422);
                    }
                } catch (java.sql.SQLException e) {
                    status = 500;
                }
            }
            byte[] resp = ("{\"status\":" + status + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        httpServer.start();
    }

    private static final String SECURED_PATH = "/api/secured-transfers";
    private static final String AUTH_TOKEN = "e2e-token";
    private static final String AUTH_TOKEN_FIELD = "token";

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
        writeProvenanceReport(endpointDir, List.of());
    }

    /**
     * @param unguarded {@code synthesize-triple}이 갭 마커 채움 슬롯으로 소비할 unguarded 필드 목록
     *                  (REQ-007) — 비어 있으면 종전대로 body가 빈 후보가 나온다.
     */
    private static void writeProvenanceReport(
            Path endpointDir,
            List<io.graphrag.builder.provenance.ProvenanceReport.UnguardedField> unguarded) throws Exception {
        var guard = new io.graphrag.builder.provenance.ProvenanceReport.GuardFact(
                "Fixture.java:1", "EXISTS",
                List.of(new io.graphrag.builder.provenance.ProvenanceReport.ValueRef(
                        io.graphrag.builder.provenance.ProvenanceReport.Origin.DB_READ,
                        null, "accounts", "id", null, null, "String", null, null)));
        var report = new io.graphrag.builder.provenance.ProvenanceReport(
                "post-api-transfers", List.of(guard), unguarded, List.of());
        Files.writeString(endpointDir.resolve("provenance-report.json"),
                Json.mapper().writeValueAsString(report));
    }

    /**
     * 에이전트 채움 단계(문서화된 파이프라인의 3번째 단계) 시뮬레이션 — 후보 {@code body.json}의 갭
     * 마커 자리에만 값을 채우고 {@code base/} 사본은 건드리지 않는다(REQ-009 마커-diff가 허용하는
     * 유일한 변경). 마커가 아닌 자리를 건드리면 T1이 거부하므로, 이 헬퍼가 하는 일이 곧 계약이다.
     *
     * @return 채우기 전 마커가 있던 필드 이름들(마커가 실제로 있었는지 단언용)
     */
    private static List<String> fillGapMarkers(Path candDir, Map<String, Object> valuesByField) throws Exception {
        Path bodyFile = candDir.resolve("body.json");
        ObjectNode body = (ObjectNode) Json.mapper().readTree(bodyFile.toFile());
        List<String> filled = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : valuesByField.entrySet()) {
            var node = body.get(entry.getKey());
            if (node == null || !node.isTextual()
                    || !node.asText().startsWith(
                            io.graphrag.builder.provenance.TripleSynthesizer.GAP_MARKER_PREFIX)) {
                continue;
            }
            filled.add(entry.getKey());
            if (entry.getValue() instanceof Long longValue) {
                body.put(entry.getKey(), longValue);
            } else {
                body.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        Files.writeString(bodyFile, Json.mapper().writeValueAsString(body));
        return filled;
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
     * 경로) → {@code synthesize-triple} → 에이전트 갭 마커 채움 → {@code trial}({@code --provenance-report}
     * 없이) — 을 돌렸을 때 후보가 T1을 통과해 실제로 시험되고 승격까지 도달해야 한다.
     *
     * <p>고치기 전의 결함: {@code synthesize-triple}이 입력 리포트를 저장 레이아웃 규약 위치
     * ({@code <triple-store>/<endpointId>/provenance-report.json})로 복사하지 않아, 문서가 안내하는
     * 순서대로 실행하면 규약 위치에 파일이 없어 {@code trial}이 {@code IllegalArgumentException}으로
     * 즉시 실패했다(스킬 워크플로 파손).
     *
     * <p><b>단언 강화(Phase A 후속 Important 2):</b> 이전 판은 종료 코드를 {@code isIn(0, 3)}으로만
     * 봤다 — 모든 후보가 T1에서 거부돼도, 심지어 body가 비어 SUT 호출이 예외로 끝나도 통과하는
     * 단언이라 "문서화된 파이프라인이 실제로 동작함"을 전혀 증명하지 못했다(실제로 강화 이전의
     * 이 테스트는 unguarded 필드가 없어 body가 {@code {}}로 합성됐고, SUT가 응답을 못 내
     * {@code IOException}으로 exit 3에 도달하고 있었다). 이제는 <b>exit 0(승격 성공)</b>을 요구하고,
     * 승격된 후보가 마커를 채운 그 후보임을 {@code promoted/} 산출물 내용으로 확인한다.
     */
    @Test
    @DisplayName("N3: provenance → synthesize-triple → 마커 채움 → trial(플래그 없이) 문서 순서가 승격까지 완주한다")
    void documentedPipelineOrderWorksWithoutExplicitReportFlag() throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
            // 후보 seed.sql이 아니라 SUT가 이미 갖고 있는 기존 데이터(잔액 1000) — happy 시드가 아니므로
            // trial의 reset(①) 대상이 아니고, 채운 body(amount=500)에 대해 200이 나오는 조건이 된다.
            st.execute("INSERT INTO accounts (id, balance) VALUES ('acc-1', 1000)");
        }
        // ① provenance 산출물은 triple-store 밖의 임의 경로에 있다(provenance CLI의 --out 계약).
        Path analysisDir = Files.createDirectories(tempDir.resolve("analysis"));
        Path externalReport = analysisDir.resolve("provenance-report.json");
        writeProvenanceReport(analysisDir, List.of(
                new io.graphrag.builder.provenance.ProvenanceReport.UnguardedField(
                        "accountId", "java.lang.String", "none"),
                new io.graphrag.builder.provenance.ProvenanceReport.UnguardedField(
                        "amount", "java.lang.Long", "none")));
        Path tripleStore = tempDir.resolve("pipeline-triples");

        // ② synthesize-triple — 후보와 함께 규약 위치로 리포트가 복사되어야 한다.
        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", externalReport.toString(),
                "--triple-store", tripleStore.toString()
        });

        Path endpointDir = tripleStore.resolve("post-api-transfers");
        Path canonicalReport = endpointDir.resolve("provenance-report.json");
        assertThat(canonicalReport)
                .as("synthesize-triple은 입력 리포트를 trial이 읽는 규약 위치로 복사해야 한다")
                .exists();
        assertThat(Files.readString(canonicalReport)).isEqualTo(Files.readString(externalReport));

        // ③ 에이전트 채움 단계 — 갭 마커 자리에만 값을 넣는다(base/ 사본은 그대로 두어 REQ-009 준수).
        Path candDir = endpointDir.resolve("cand-01");
        assertThat(candDir).as("synthesize-triple이 후보를 하나는 만들어야 한다").isDirectory();
        assertThat(fillGapMarkers(candDir, Map.of("accountId", "acc-1", "amount", 500L)))
                .as("합성된 body의 unguarded 두 자리는 갭 마커여야 하고, 그 두 자리만 채워야 한다")
                .containsExactlyInAnyOrder("accountId", "amount");

        // ④ trial — --provenance-report 없이도 규약 위치를 찾아 후보를 T1 통과 → 실제 시험 → 승격해야 한다.
        Path happySeeds = writeHappySeeds();
        int exitCode = BuilderCli.runTrial(baseOptions(tripleStore, happySeeds));

        assertThat(exitCode)
                .as("문서 순서대로 돌린 후보가 T1을 통과해 실제로 시험되고 2xx로 승격되면 exit 0이어야 한다 "
                        + "(T1 거부·invoke 예외로 끝나면 3이 되므로 이 단언이 '실제 trial 수행'의 증거다)")
                .isEqualTo(0);
        assertThat(candDir).as("승격된 후보는 원위치에서 사라져야 한다").doesNotExist();
        assertThat(endpointDir.resolve("failed"))
                .as("T1 거부/실패 후보가 없어야 한다(하나라도 있으면 파이프라인이 실제로 동작한 게 아니다)")
                .doesNotExist();

        Path promoted = endpointDir.resolve("promoted").resolve("cand-01");
        assertThat(promoted).as("성공 후보는 promoted/cand-01로 이동해야 한다").isDirectory();
        var promotedBody = Json.mapper().readTree(promoted.resolve("body.json").toFile());
        assertThat(promotedBody.get("accountId").asText())
                .as("승격된 것은 마커를 채운 바로 그 후보여야 한다").isEqualTo("acc-1");
        assertThat(promotedBody.get("amount").asLong()).isEqualTo(500L);
        assertThat(Files.readString(promoted.resolve("seed.sql")))
                .as("이 후보의 seed.sql은 합성 단계에서 비어 있었고 채움 대상도 아니다(비-마커 변경 없음 증거)")
                .isBlank();
    }

    // ---- REQ-013 인증 배선(E2E-B1 차단 원인 1) ----

    /** 인증이 걸린 경로를 대상으로 하는 옵션 세트. {@code extra}로 --auth-* 를 덧붙인다. */
    private Map<String, String> securedOptions(Path tripleStore, Path happySeedsFile, Map<String, String> extra) {
        Map<String, String> options = new java.util.LinkedHashMap<>(baseOptions(tripleStore, happySeedsFile));
        options.put("--path", SECURED_PATH);
        options.putAll(extra);
        return options;
    }

    /** 인증 회귀 두 테스트가 공유하는 fixture: 잔액 1000짜리 계좌 + 유효 후보 1개. */
    private Path securedFixture(Path tripleStore) throws Exception {
        try (Statement st = sutConnection.createStatement()) {
            st.execute("DELETE FROM accounts");
            st.execute("INSERT INTO accounts (id, balance) VALUES ('acc-1', 1000)");
        }
        Path endpointDir = Files.createDirectories(tripleStore.resolve("post-api-transfers"));
        writeProvenanceReport(endpointDir);
        writeCandidate(endpointDir, "cand-01", "{\"accountId\":\"acc-1\",\"amount\":500}", "");
        return endpointDir;
    }

    /**
     * 결함 재현(고치기 전 RED가 아니라 <b>대조군</b>): 인증이 걸린 SUT에 {@code --auth-*} 없이 trial을
     * 돌리면 후보 내용이 아무리 유효해도 403으로 실패한다 — 이 테스트는 아래 GREEN 테스트가 "후보가
     * 원래 유효해서" 통과한 게 아니라 "인증 배선 때문에" 통과했음을 증명한다.
     */
    @Test
    @DisplayName("REQ-013: --auth-* 없이 인증 SUT에 trial을 돌리면 유효 후보도 403으로 실패한다(대조군)")
    void req013_withoutAuthFlagsSecuredSutRejectsEveryCandidate() throws Exception {
        Path tripleStore = tempDir.resolve("secured-noauth-triples");
        Path endpointDir = securedFixture(tripleStore);
        Path happySeeds = writeHappySeeds();

        int exitCode = BuilderCli.runTrial(securedOptions(tripleStore, happySeeds, Map.of()));

        assertThat(exitCode).as("전부 실패하면 예산 소진으로 exit 3").isEqualTo(3);
        var digests = Json.mapper().readTree(
                endpointDir.resolve("failed").resolve("digest-final.json").toFile());
        assertThat(digests.get(0).get("status").asInt())
                .as("실패 원인이 후보 내용이 아니라 인증(403)임을 고정한다")
                .isEqualTo(403);
    }

    /**
     * REQ-013 인증 배선 — {@code build} 경로와 동일한 {@code --auth-*} 플래그를 {@code trial}에도
     * 배선했으므로, 동일한 후보·동일한 SUT에서 이제 403이 아니라 실제 판정(2xx → 승격)이 나와야 한다.
     * 배선 이전에는 {@code RequestHeaders.empty()} + {@code authProvider=null} 하드코딩 탓에 이 테스트가
     * 위 대조군과 똑같이 403/exit 3으로 끝났다(E2E-B1 실증 차단 원인 1).
     */
    @Test
    @DisplayName("REQ-013: --auth-* 를 주면 trial이 로그인 토큰을 붙여 실제 판정에 도달하고 승격한다")
    void req013_authFlagsAreWiredIntoTrialInvoke() throws Exception {
        Path tripleStore = tempDir.resolve("secured-auth-triples");
        Path endpointDir = securedFixture(tripleStore);
        Path happySeeds = writeHappySeeds();

        int exitCode = BuilderCli.runTrial(securedOptions(tripleStore, happySeeds, Map.of(
                "--auth-login-path", "/api/auth/login",
                "--auth-user", "admin",
                "--auth-pass", "password")));

        assertThat(exitCode)
                .as("토큰이 붙으면 403이 사라지고 후보가 2xx 판정으로 승격되어 exit 0이어야 한다")
                .isEqualTo(0);
        assertThat(endpointDir.resolve("promoted").resolve("cand-01")).isDirectory();
        assertThat(endpointDir.resolve("failed"))
                .as("403으로 인한 실패 후보가 하나도 없어야 한다")
                .doesNotExist();
    }
}
