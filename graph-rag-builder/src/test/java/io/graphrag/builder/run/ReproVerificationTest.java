package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-013/014/015: non-2xx path 재현 검증 + 억제 로직 단위 테스트.
 *
 * <p>시나리오:
 * - path-poll: GET /items/1 → 탐색 중 500 (오염된 DB 행 때문에), 클린 DB 재실행 → 404 (다름) → DROP
 * - path-det-404: GET /items/0 → 탐색 중 404, 클린 DB 재실행 → 404 (같음) → KEEP
 * - path-det-400: POST /items → 탐색 중 400 (입력 검증), 클린 DB 재실행 → 400 (같음) → KEEP
 * - path-happy: GET /items/1 → 탐색 중 200, 2xx는 재현 검증 범위 외 → 항상 KEEP
 * - path-non-get-5xx: POST /items → 탐색 중 500, non-GET mutating은 conservative → 항상 KEEP
 */
class ReproVerificationTest {

    private static final Endpoint GET_ENDPOINT = new Endpoint(
            "ep-items-get", "GET", "/items/{id}", "x.C", "get",
            List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);

    private static final Endpoint POST_ENDPOINT = new Endpoint(
            "ep-items-post", "POST", "/items", "x.C", "create",
            List.of(new EndpointParam("request", "x.CreateRequest", ParamKind.BODY)), false);

    private static ObjectNode input(int id) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("id", id);
        return node;
    }

    private static ObjectNode emptyInput() {
        return Json.mapper().createObjectNode();
    }

    private static ExploredPath path(String pathId, String endpointId, int status, ObjectNode body) {
        return new ExploredPath(pathId, endpointId, body, status, null,
                List.of(), List.of(),
                List.of(new BranchRef("x.C", "m", 10, 0)),
                "heuristic", List.of(), List.of(), List.of());
    }

    /** 재현 검증 없이 비교할 수 있는 드롭 기록 편의 생성. */
    private static ExplorationReport.DroppedPath dropped(String endpointId, String pathId,
                                                          int captured, int replay) {
        return new ExplorationReport.DroppedPath(endpointId, pathId, captured, replay,
                "status_mismatch");
    }

    // ─── G4-(b) 핵심 케이스: 탐색 중 500, 클린 DB 재실행 → 404 (다름) → DROP ────────────────
    @Test
    void nonTwoxx_get_nonReproducible_isDropped() {
        ExploredPath polluted500 = path("path-poll", GET_ENDPOINT.id(), 500, input(1));
        ExploredPath det404 = path("path-det-404", GET_ENDPOINT.id(), 404, input(0));
        List<ExploredPath> input = List.of(polluted500, det404);
        List<RequiredSeed> seeds = List.of();

        // 재현 verifier: path-poll(500) → 재실행 404 (다름), path-det-404(404) → 재실행 404 (같음)
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) ->
                p.id().equals("path-poll") ? 404 : p.expectedStatus();

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, input, seeds, verifier);

        // path-poll(500) 은 DROP; path-det-404(404) 는 KEEP
        assertThat(result.kept()).extracting(ExploredPath::id)
                .containsExactlyInAnyOrder("path-det-404");
        assertThat(result.dropped()).hasSize(1);
        ExplorationReport.DroppedPath drop = result.dropped().get(0);
        assertThat(drop.endpointId()).isEqualTo(GET_ENDPOINT.id());
        assertThat(drop.pathId()).isEqualTo("path-poll");
        assertThat(drop.capturedStatus()).isEqualTo(500);
        assertThat(drop.replayStatus()).isEqualTo(404);
        assertThat(drop.reason()).isEqualTo("status_mismatch");
    }

    // ─── 재현 가능한 500 (진짜 버그) → KEEP ──────────────────────────────────────────────────
    @Test
    void nonTwoxx_get_reproducible500_isKept() {
        ExploredPath real500 = path("path-real-500", GET_ENDPOINT.id(), 500, input(1));
        List<ExploredPath> input = List.of(real500);
        List<RequiredSeed> seeds = List.of();

        // 재실행도 500 → 재현 가능
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> 500;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, input, seeds, verifier);

        assertThat(result.kept()).extracting(ExploredPath::id)
                .containsExactly("path-real-500");
        assertThat(result.dropped()).isEmpty();
    }

    // ─── 결정론적 404 (없는 id) → KEEP ──────────────────────────────────────────────────────
    @Test
    void nonTwoxx_get_deterministicNotFound_isKept() {
        ExploredPath det404 = path("path-det-404", GET_ENDPOINT.id(), 404, input(0));
        List<ExploredPath> inputPaths = List.of(det404);
        List<RequiredSeed> seeds = List.of();

        // 재실행도 404
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> 404;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, inputPaths, seeds, verifier);

        assertThat(result.kept()).hasSize(1);
        assertThat(result.dropped()).isEmpty();
    }

    // ─── 결정론적 400 (POST 검증 실패) → KEEP ────────────────────────────────────────────────
    @Test
    void nonTwoxx_post_deterministicBadRequest_isKept() {
        ExploredPath bad400 = path("path-det-400", POST_ENDPOINT.id(), 400, emptyInput());
        List<ExploredPath> inputPaths = List.of(bad400);
        List<RequiredSeed> seeds = List.of();

        // POST지만 non-2xx 재실행 400 → 재현 가능 → KEEP
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> 400;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        POST_ENDPOINT, inputPaths, seeds, verifier);

        assertThat(result.kept()).hasSize(1);
        assertThat(result.dropped()).isEmpty();
    }

    // ─── 2xx path는 검증 범위 외 → 항상 KEEP, verifier 호출 없음 ────────────────────────────
    @Test
    void twoxx_path_alwaysKeptWithoutVerifierCall() {
        ExploredPath ok200 = path("path-ok", GET_ENDPOINT.id(), 200, input(1));
        ExploredPath created201 = path("path-created", POST_ENDPOINT.id(), 201, emptyInput());
        List<ExploredPath> inputPaths = List.of(ok200, created201);
        List<RequiredSeed> seeds = List.of();

        // verifier가 호출되면 예외 → 호출되지 않아야 한다
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> {
            throw new AssertionError("verifier must not be called for 2xx paths");
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, inputPaths, seeds, verifier);

        assertThat(result.kept()).hasSize(2);
        assertThat(result.dropped()).isEmpty();
    }

    // ─── non-GET 5xx mutating 경로: conservative = 재현 불가 판별 불가 → KEEP ─────────────────
    @Test
    void nonGet_5xx_conservativeAlwaysKept() {
        ExploredPath post500 = path("path-post-500", POST_ENDPOINT.id(), 500, emptyInput());
        List<ExploredPath> inputPaths = List.of(post500);
        List<RequiredSeed> seeds = List.of();

        // non-GET non-2xx에서 verifier가 다른 상태 반환 → conservative이면 KEEP
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> 404;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        POST_ENDPOINT, inputPaths, seeds, verifier);

        // Conservative 정책: mutating non-2xx는 안전하게 KEEP (DROP은 안 함)
        assertThat(result.kept()).extracting(ExploredPath::id)
                .containsExactly("path-post-500");
        assertThat(result.dropped()).isEmpty();
    }

    // ─── negative-auth / negative-validation 마커 path: discoveredBy 마커가 있으면 SKIP ───────
    @Test
    void negativeMarkerPaths_areNotVerified_alwaysKept() {
        ExploredPath negAuth = new ExploredPath("p-negauth", GET_ENDPOINT.id(),
                input(1), 401, null, List.of(), List.of(), List.of(), "negative-auth",
                List.of(), List.of(), List.of());
        ExploredPath negVal = new ExploredPath("p-negval", GET_ENDPOINT.id(),
                emptyInput(), 400, null, List.of(), List.of(), List.of(), "negative-validation",
                List.of(), List.of(), List.of());
        List<ExploredPath> inputPaths = List.of(negAuth, negVal);
        List<RequiredSeed> seeds = List.of();

        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> {
            throw new AssertionError("verifier must not be called for marker paths");
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, inputPaths, seeds, verifier);

        assertThat(result.kept()).hasSize(2);
        assertThat(result.dropped()).isEmpty();
    }

    // ─── egress-assertion 마커 path: GET + non-2xx + verifier가 2xx 반환 → KEEP (변형 stub 의존) ─
    @Test
    void egressAssertionPath_getWithNon2xx_isKeptEvenWhenVerifierReturns2xx() {
        // GET 엔드포인트에 non-2xx(404) egress-assertion 경로: verifier는 2xx(200) 반환 → 기존 로직이면 DROP
        ExploredPath egressPath = new ExploredPath("p-egress-404", GET_ENDPOINT.id(),
                input(1), 404, null, List.of(), List.of(), List.of(), "egress-assertion",
                List.of(), List.of(), List.of());
        List<ExploredPath> inputPaths = List.of(egressPath);
        List<RequiredSeed> seeds = List.of();

        // clean-replay에서는 변형 stub 없이 SUT가 2xx 반환 → 기존 필터라면 DROP 판정
        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> 200;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, inputPaths, seeds, verifier);

        // egress-assertion path는 변형 stub 의존이므로 verifier 호출 없이 항상 KEEP
        assertThat(result.kept()).extracting(ExploredPath::id)
                .containsExactly("p-egress-404");
        assertThat(result.dropped()).isEmpty();
    }

    // ─── verifier 예외 발생 시: conservative KEEP + drops에 기록 안 함 ─────────────────────────
    @Test
    void verifierException_conservativelyKeepsPath() {
        ExploredPath path500 = path("path-err-500", GET_ENDPOINT.id(), 500, input(1));
        List<ExploredPath> inputPaths = List.of(path500);
        List<RequiredSeed> seeds = List.of();

        EndpointExplorationRunner.ReproVerifier verifier = (endpoint, p, requiredSeeds) -> {
            throw new IllegalStateException("HTTP connection refused");
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_ENDPOINT, inputPaths, seeds, verifier);

        // 검증 실패 시 안전하게 KEEP (없애지 않는다)
        assertThat(result.kept()).hasSize(1);
        assertThat(result.dropped()).isEmpty();
    }

    // ─── ExplorationReport.EndpointExploration 후방 호환 생성자 ──────────────────────────────
    @Test
    void endpointExploration_sixArgConstructor_droppedPathsDefaultsEmpty() {
        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-items-get", 10, 7,
                List.of(new BranchRef("x.C", "m", 12, 0)),
                Map.of("heuristic", 2), 3);

        assertThat(exploration.droppedPaths()).isEmpty();
    }

    // ─── ExplorationReport.EndpointExploration 7-arg 생성자 (droppedPaths 포함) ────────────────
    @Test
    void endpointExploration_sevenArgConstructor_droppedPathsPresent() {
        List<ExplorationReport.DroppedPath> drops = List.of(
                new ExplorationReport.DroppedPath("ep-items-get", "path-poll", 500, 404,
                        "status_mismatch"));

        ExplorationReport.EndpointExploration exploration = new ExplorationReport.EndpointExploration(
                "ep-items-get", 10, 7,
                List.of(new BranchRef("x.C", "m", 12, 0)),
                Map.of("heuristic", 2), 3, drops);

        assertThat(exploration.droppedPaths()).hasSize(1);
        assertThat(exploration.droppedPaths().get(0).pathId()).isEqualTo("path-poll");
        assertThat(exploration.droppedPaths().get(0).capturedStatus()).isEqualTo(500);
        assertThat(exploration.droppedPaths().get(0).replayStatus()).isEqualTo(404);
    }

    // ─── ExplorationReport.DroppedPath JSON 직렬화 왕복 ──────────────────────────────────────
    @Test
    void droppedPath_roundTrips() throws Exception {
        ExplorationReport.DroppedPath drop =
                new ExplorationReport.DroppedPath("ep", "p1", 500, 404, "status_mismatch");
        String json = Json.mapper().writeValueAsString(drop);
        ExplorationReport.DroppedPath back =
                Json.mapper().readValue(json, ExplorationReport.DroppedPath.class);
        assertThat(back).isEqualTo(drop);
    }

    // ─── ExplorationReport JSON 후방 호환: droppedPaths 필드 없는 구 JSON 읽기 ──────────────────
    @Test
    void explorationReport_legacyJson_droppedPathsDefaultsEmpty() throws Exception {
        String legacyJson = """
                {
                  "endpoints": [
                    {
                      "endpointId": "ep-items-get",
                      "totalBranches": 10,
                      "coveredBranches": 7,
                      "missedBranches": [],
                      "pathsByEngine": {"heuristic": 2},
                      "solverRelevantMissed": 3
                    }
                  ],
                  "coveredAppBranches": 24,
                  "totalAppBranches": 58,
                  "coveredAppClasses": []
                }
                """;
        ExplorationReport report = Json.mapper().readValue(legacyJson, ExplorationReport.class);
        assertThat(report.endpoints()).hasSize(1);
        assertThat(report.endpoints().get(0).droppedPaths()).isEmpty();
    }
}
