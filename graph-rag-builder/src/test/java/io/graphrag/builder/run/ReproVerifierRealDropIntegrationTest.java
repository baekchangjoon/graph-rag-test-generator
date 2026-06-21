package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-014: 비재현 non-2xx path 실제 DROP 통합 테스트.
 *
 * <p>단위 테스트(ReproVerificationTest)는 주입된 stub verifier로 결정 논리만 검증한다.
 * 이 테스트는 실제 DB(Postgres Testcontainers) + 실제 HTTP 서버(com.sun.net.httpserver)로
 * 진짜 replay 경로(Seeds.insert/delete + HttpClient 호출)가 MISMATCH를 감지하고
 * 경로를 DROP하는지 end-to-end로 검증한다.
 *
 * <p>시나리오:
 * - items 테이블: id(PK), name VARCHAR.
 * - HTTP 서버(/items/{id}): DB에 해당 id 행이 있으면 500, 없으면 404 반환.
 *   (탐색 중 오염된 DB → 500 캡처; 클린 DB 재실행 → 404: 비재현)
 * - path-polluted: 탐색 중 500 캡처, requiredSeedIds=[] (attachSeeds 설계와 동일).
 *   replay(클린 DB) → 404 → MISMATCH → DROP.
 * - path-clean-404: 탐색 중 404 캡처(id=9999), replay → 404 → MATCH → KEEP.
 */
@Tag("docker")
@Testcontainers
class ReproVerifierRealDropIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    private static HttpServer httpServer;
    private static int httpPort;
    private static Connection connection;
    private static final long POLLUTED_ID = 1L;
    private static final long ABSENT_ID = 9999L;

    private static final Endpoint GET_ITEMS = new Endpoint(
            "get-items-id", "GET", "/items/{id}", "ItemController", "get",
            List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);

    @BeforeAll
    static void setup() throws Exception {
        // 1. Postgres: items 테이블 생성
        connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(255))");
        }

        // 2. HTTP 서버: DB 상태에 따라 응답 — 행 존재 → 500, 없음 → 404
        //    (탐색 중 오염된 DB가 500을 내고, 클린 재실행에서 404를 냄을 시뮬레이션)
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpPort = httpServer.getAddress().getPort();
        httpServer.createContext("/items/", exchange -> {
            // path: /items/{id} — 마지막 세그먼트가 id
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            long id = Long.parseLong(parts[parts.length - 1]);

            boolean exists;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM items WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            // 행이 있으면 500 (오염된 DB 상태 시뮬레이션), 없으면 404 (클린 DB)
            int status = exists ? 500 : 404;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void teardown() throws Exception {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * REQ-014: 탐색 중 오염된 DB가 만든 500이, 클린 DB 재실행에서 404로 바뀌면 DROP된다.
     * - 실제 Seeds.insert / Seeds.delete / HttpClient를 사용하는 ReproVerifier를 조립.
     * - verifyAndFilterNonTwoxx의 실 흐름이 DROP을 생성하고 droppedPaths에 기록하는지 단언.
     */
    @Test
    @DisplayName("REQ-014: real replay detects status mismatch (500→404) and drops the non-reproducible path")
    void realReplay_statusMismatch_pathIsDropped() throws Exception {
        String baseUrl = "http://localhost:" + httpPort;

        // 탐색 중 오염된 DB: items 테이블에 id=1 행을 미리 삽입 (탐색 환경 시뮬레이션)
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO items(id, name) VALUES(?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, POLLUTED_ID);
            ps.setString(2, "probe-item");
            ps.executeUpdate();
        }

        // 탐색 중 500 캡처된 path — requiredSeedIds=[] (GET non-2xx의 attachSeeds 설계)
        ObjectNode pollutedInput = Json.mapper().createObjectNode();
        pollutedInput.put("id", POLLUTED_ID);
        ExploredPath pathPolluted = new ExploredPath(
                "path-polluted-500", GET_ITEMS.id(), pollutedInput, 500, null,
                List.of(), List.of(),
                List.of(new BranchRef("ItemController", "get", 10, 0)),
                "heuristic", List.of(), List.of(), List.of() /* requiredSeedIds=[] */);

        // 탐색 중 404 캡처된 path (id=9999 → 항상 없는 행) — 재현 가능한 non-2xx → KEEP
        ObjectNode absentInput = Json.mapper().createObjectNode();
        absentInput.put("id", ABSENT_ID);
        ExploredPath pathClean404 = new ExploredPath(
                "path-clean-404", GET_ITEMS.id(), absentInput, 404, null,
                List.of(), List.of(),
                List.of(new BranchRef("ItemController", "get", 15, 0)),
                "heuristic", List.of(), List.of(), List.of());

        // 2xx happy path — 검증 범위 외, 항상 KEEP (verifier 미호출)
        ObjectNode happyInput = Json.mapper().createObjectNode();
        happyInput.put("id", POLLUTED_ID);
        ExploredPath pathHappy200 = new ExploredPath(
                "path-happy-200", GET_ITEMS.id(), happyInput, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());

        List<ExploredPath> allPaths = List.of(pathPolluted, pathClean404, pathHappy200);
        List<RequiredSeed> allSeeds = List.of();  // GET non-2xx: requiredSeedIds=[]이므로 finalSeeds에 없음

        // 실제 replay ReproVerifier: Seeds.insert/delete + real HttpClient
        // (EndpointExplorationRunner.httpReproVerifier 람다와 동일한 흐름)
        HttpClient replayHttp = HttpClient.newHttpClient();
        EndpointExplorationRunner.ReproVerifier realVerifier = (ep, path, declaredSeeds) -> {
            // step 1: 탐색 중 삽입된 행을 제거 (deleteSeeds 시뮬레이션)
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM items WHERE id = " + POLLUTED_ID);
            }
            try {
                // step 2: 이 path의 선언 시드 재삽입 (GET non-2xx는 [] → 삽입 없음)
                List<SynthesizedInput.SeedRow> insertedRows = new ArrayList<>();
                for (RequiredSeed rs : declaredSeeds) {
                    SynthesizedInput.SeedRow row = new SynthesizedInput.SeedRow(
                            rs.table(), rs.columns(),
                            rs.values().stream().map(v -> (Object) v).toList());
                    Seeds.insert(connection, DbConfig.Type.POSTGRES, row);
                    insertedRows.add(row);
                }
                // step 3+4: 재실행 후 삽입 시드 역순 정리 (Part A 수정 반영)
                try {
                    long pathId = path.sampleInput().get("id").asLong();
                    String url = baseUrl + "/items/" + pathId;
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .GET().build();
                    return replayHttp.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                } finally {
                    for (int i = insertedRows.size() - 1; i >= 0; i--) {
                        Seeds.delete(connection, insertedRows.get(i));
                    }
                }
            } finally {
                // step 5: happy 시드 복원 (이 테스트에서는 재삽입하여 후속 assertions 준비)
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO items(id, name) VALUES(?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setLong(1, POLLUTED_ID);
                    ps.setString(2, "probe-item");
                    ps.executeUpdate();
                }
            }
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ITEMS, allPaths, allSeeds, realVerifier);

        // 단언 1: 비재현 path(500→404) 는 DROP됨
        assertThat(result.kept()).extracting(ExploredPath::id)
                .doesNotContain("path-polluted-500");

        // 단언 2: droppedPaths에 캡처 상태 500 → 재실행 404, reason=status_mismatch 기록
        assertThat(result.dropped()).hasSize(1);
        ExplorationReport.DroppedPath drop = result.dropped().get(0);
        assertThat(drop.endpointId()).isEqualTo(GET_ITEMS.id());
        assertThat(drop.pathId()).isEqualTo("path-polluted-500");
        assertThat(drop.capturedStatus()).isEqualTo(500);
        assertThat(drop.replayStatus()).isEqualTo(404);
        assertThat(drop.reason()).isEqualTo("status_mismatch");

        // 단언 3: 재현 가능한 non-2xx path(clean-404) 는 KEEP
        assertThat(result.kept()).extracting(ExploredPath::id)
                .contains("path-clean-404");

        // 단언 4: 2xx path는 검증 없이 항상 KEEP
        assertThat(result.kept()).extracting(ExploredPath::id)
                .contains("path-happy-200");
    }

    /**
     * REQ-014 보조: 재현 가능한 non-2xx (탐색/재실행 모두 404) 는 KEEP된다.
     * DB에 id=1 행이 없는 상태에서 탐색도 404, 재실행도 404 → MATCH → KEEP.
     */
    @Test
    @DisplayName("REQ-014: real replay detects status match (404→404) and keeps the reproducible path")
    void realReplay_statusMatch_pathIsKept() throws Exception {
        String baseUrl = "http://localhost:" + httpPort;

        // DB에 id=1 행이 없는지 확인 (다른 테스트 후 잔여가 있을 수 있으므로 정리)
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM items WHERE id = " + POLLUTED_ID);
        }

        // 탐색 중 404 캡처 (DB가 비어있어 탐색 시점에도 404 → 결정론적 non-2xx)
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("id", POLLUTED_ID);
        ExploredPath det404 = new ExploredPath(
                "path-det-404", GET_ITEMS.id(), input, 404, null,
                List.of(), List.of(),
                List.of(new BranchRef("ItemController", "get", 15, 0)),
                "heuristic", List.of(), List.of(), List.of());

        HttpClient replayHttp = HttpClient.newHttpClient();
        EndpointExplorationRunner.ReproVerifier realVerifier = (ep, path, declaredSeeds) -> {
            long pathId = path.sampleInput().get("id").asLong();
            String url = baseUrl + "/items/" + pathId;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                return replayHttp.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("replay interrupted", e);
            }
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ITEMS, List.of(det404), List.of(), realVerifier);

        assertThat(result.kept()).extracting(ExploredPath::id)
                .containsExactly("path-det-404");
        assertThat(result.dropped()).isEmpty();
    }
}
