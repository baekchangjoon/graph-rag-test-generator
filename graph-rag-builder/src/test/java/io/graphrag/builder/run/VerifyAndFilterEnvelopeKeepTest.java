package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.explore.ExplorationOutcome;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.oracle.ErrorEnvelopeClassifier;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.ParamKind;
import io.graphrag.model.RequiredSeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-007: error-envelope path (outcome=FAILURE, 와이어 status=2xx) 마커 + 필터 KEEP.
 *
 * <p>시나리오:
 * - enveloped-200 path → buildPaths가 discoveredBy="error-envelope" 부여
 * - verifyAndFilterNonTwoxx에서 DROP되지 않고 KEEP
 * - 진짜 2xx SUCCESS path / 진짜 non-2xx path는 영향 없음
 */
@DisplayName("REQ-007: error-envelope path 마커 + 필터 KEEP")
class VerifyAndFilterEnvelopeKeepTest {

    private static final Endpoint GET_EP = new Endpoint(
            "ep-get", "GET", "/items/{id}", "x.C", "get",
            List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false);

    private static final Endpoint POST_EP = new Endpoint(
            "ep-post", "POST", "/items", "x.C", "create",
            List.of(new EndpointParam("request", "x.Req", ParamKind.BODY)), false);

    // ────────────────────────────────────────────────────────────────────────────
    //  buildPaths 마커 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REQ-007: enveloped-200 → buildPaths가 discoveredBy='error-envelope' 부여")
    void buildPaths_enveloped200_setsDiscoveredByMarker() {
        ObjectNode body = Json.mapper().createObjectNode().put("errorCode", "404").put("msg", "not found");
        ResponseClassifier classifier = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");
        EnvelopeFilterAccessor accessor = new EnvelopeFilterAccessor(classifier);

        PathCandidate candidate = new PathCandidate(
                "p-env", Json.mapper().createObjectNode(),
                200, body, List.of(), "heuristic", 0L, 0L,
                List.of(), List.of(), List.of(), "trace-env");
        ExplorationOutcome outcome = new ExplorationOutcome(List.of(candidate), Set.of(), Map.of());

        List<ExploredPath> paths = accessor.callBuildPaths(outcome, GET_EP);

        ExploredPath envPath = paths.stream()
                .filter(p -> p.id().equals("p-env"))
                .findFirst().orElseThrow();
        assertThat(envPath.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(envPath.expectedStatus()).isEqualTo(200);   // 와이어 status 보존
        assertThat(envPath.discoveredBy()).isEqualTo("error-envelope");
    }

    @Test
    @DisplayName("REQ-007: 진짜 200(SUCCESS) path → discoveredBy는 candidate의 값 그대로")
    void buildPaths_genuine200_keepsOriginalDiscoveredBy() {
        ObjectNode body = Json.mapper().createObjectNode().put("id", 7);
        ResponseClassifier classifier = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");
        EnvelopeFilterAccessor accessor = new EnvelopeFilterAccessor(classifier);

        PathCandidate candidate = new PathCandidate(
                "p-ok", Json.mapper().createObjectNode(),
                200, body, List.of(), "heuristic", 0L, 0L,
                List.of(), List.of(), List.of(), "trace-ok");
        ExplorationOutcome outcome = new ExplorationOutcome(List.of(candidate), Set.of(), Map.of());

        List<ExploredPath> paths = accessor.callBuildPaths(outcome, GET_EP);

        ExploredPath okPath = paths.stream()
                .filter(p -> p.id().equals("p-ok"))
                .findFirst().orElseThrow();
        assertThat(okPath.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(okPath.discoveredBy()).isEqualTo("heuristic");   // 변경 없음
    }

    @Test
    @DisplayName("REQ-007: 진짜 non-2xx path(StatusOnlyClassifier) → discoveredBy는 candidate의 값 그대로")
    void buildPaths_genuine404_keepsOriginalDiscoveredBy() {
        // StatusOnlyClassifier: 404 → FAILURE (에러 엔벨로프 없음)
        EnvelopeFilterAccessor accessor = new EnvelopeFilterAccessor(new StatusOnlyClassifier());

        PathCandidate candidate = new PathCandidate(
                "p-404", Json.mapper().createObjectNode(),
                404, Json.mapper().nullNode(), List.of(), "heuristic", 0L, 0L,
                List.of(), List.of(), List.of(), "trace-404");
        ExplorationOutcome outcome = new ExplorationOutcome(List.of(candidate), Set.of(), Map.of());

        List<ExploredPath> paths = accessor.callBuildPaths(outcome, GET_EP);

        ExploredPath p404 = paths.stream()
                .filter(p -> p.id().equals("p-404"))
                .findFirst().orElseThrow();
        assertThat(p404.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(p404.expectedStatus()).isEqualTo(404);
        assertThat(p404.discoveredBy()).isEqualTo("heuristic");   // 변경 없음
    }

    // ────────────────────────────────────────────────────────────────────────────
    //  verifyAndFilterNonTwoxx 필터 테스트
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REQ-007: error-envelope path(와이어 200, discoveredBy='error-envelope') → KEEP, verifier 호출 없음")
    void filter_envelopedPath_isKeptWithoutVerifierCall() {
        // enveloped path: 와이어 200이므로 expectedStatus=200, discoveredBy="error-envelope"
        ExploredPath enveloped = new ExploredPath(
                "p-env", GET_EP.id(),
                Json.mapper().createObjectNode(), 200, null,
                List.of(), List.of(), List.of(),
                "error-envelope", List.of(), List.of(), List.of());

        EndpointExplorationRunner.ReproVerifier verifier = (ep, p, seeds) -> {
            throw new AssertionError("verifier must not be called for error-envelope paths");
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_EP, List.of(enveloped), List.of(), verifier);

        assertThat(result.kept()).extracting(ExploredPath::id).containsExactly("p-env");
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    @DisplayName("REQ-007: 진짜 non-2xx path는 기존 로직(GET → replay 검증)에 영향 없음")
    void filter_genuine404_isVerifiedNormally() {
        ExploredPath det404 = new ExploredPath(
                "p-det-404", GET_EP.id(),
                Json.mapper().createObjectNode(), 404, null,
                List.of(), List.of(), List.of(),
                "heuristic", List.of(), List.of(), List.of());

        // 재실행도 404 → KEEP
        EndpointExplorationRunner.ReproVerifier verifier = (ep, p, seeds) -> 404;

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_EP, List.of(det404), List.of(), verifier);

        assertThat(result.kept()).extracting(ExploredPath::id).containsExactly("p-det-404");
        assertThat(result.dropped()).isEmpty();
    }

    @Test
    @DisplayName("REQ-007: 진짜 2xx SUCCESS path는 기존 KEEP 동작 그대로")
    void filter_genuine200_isAlwaysKept() {
        ExploredPath ok200 = new ExploredPath(
                "p-ok", GET_EP.id(),
                Json.mapper().createObjectNode(), 200, null,
                List.of(), List.of(), List.of(),
                "heuristic", List.of(), List.of(), List.of());

        EndpointExplorationRunner.ReproVerifier verifier = (ep, p, seeds) -> {
            throw new AssertionError("verifier must not be called for 2xx paths");
        };

        EndpointExplorationRunner.FilterResult result =
                EndpointExplorationRunner.verifyAndFilterNonTwoxx(
                        GET_EP, List.of(ok200), List.of(), verifier);

        assertThat(result.kept()).extracting(ExploredPath::id).containsExactly("p-ok");
        assertThat(result.dropped()).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────────
    //  테스트 헬퍼: buildPaths 호출을 위한 패키지-private 래퍼
    // ────────────────────────────────────────────────────────────────────────────

    static class EnvelopeFilterAccessor extends EndpointExplorationRunner {

        EnvelopeFilterAccessor(ResponseClassifier classifier) {
            super(null, (Connection) null, null, null, null, 0,
                    null, List.of(), List.of(), null, null,
                    java.util.Map.of(), java.util.Map.of(), null, null, null, classifier);
        }

        List<ExploredPath> callBuildPaths(ExplorationOutcome outcome, Endpoint endpoint) {
            return buildPaths(outcome, endpoint, List.of()).paths();
        }
    }
}
