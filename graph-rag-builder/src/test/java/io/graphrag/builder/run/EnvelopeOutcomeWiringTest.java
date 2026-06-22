package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.explore.ExplorationOutcome;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.oracle.ErrorEnvelopeClassifier;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-002/REQ-004: classifier 배선 — buildPaths가 주입된 ResponseClassifier로 각 path의 Outcome을
 * 기록하되 expectedStatus(와이어 status)는 그대로 보존하는지 검증.
 */
@DisplayName("REQ-002/REQ-004: classifier 배선 — outcome 기록 + 와이어 status 보존")
class EnvelopeOutcomeWiringTest {

    private static final Endpoint EP = new Endpoint(
            "GET /api/widget", "GET", "/api/widget", "WidgetController", "get",
            List.of(), false);

    /** ExplorationOutcome 한 개 path: status 200, 주어진 body. */
    private static ExplorationOutcome outcomeOf(int status, JsonNode body) {
        JsonNode reqBody = Json.mapper().createObjectNode();
        PathCandidate c = new PathCandidate(
                "p1", reqBody, status, body, List.of(), "heuristic", 0L, 0L, List.of(),
                List.of(), List.of(), "trace-p1");
        return new ExplorationOutcome(List.of(c), Set.of(), java.util.Map.of());
    }

    @Test
    @DisplayName("REQ-004: enveloped-200(errorCode=404) → outcome FAILURE, expectedStatus 200 보존, semanticStatusText '404'")
    void enveloped200IsFailureButPreservesWireStatus() {
        JsonNode body = Json.mapper().createObjectNode().put("errorCode", "404").put("msg", "not found");
        ResponseClassifier classifier = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");
        EnvelopeWiringAccessor accessor = new EnvelopeWiringAccessor(classifier);

        List<ExploredPath> paths = accessor.callBuildPaths(outcomeOf(200, body), EP);

        ExploredPath p = paths.stream()
                .filter(x -> x.outcome() == Outcome.Kind.FAILURE)
                .findFirst().orElseThrow();
        assertThat(p.expectedStatus()).isEqualTo(200);          // 와이어 status 보존
        assertThat(p.semanticStatusText()).isEqualTo("404");    // 엔벨로프 복원
        assertThat(p.semanticStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("REQ-002: 기본 StatusOnlyClassifier — 진짜 200 → outcome SUCCESS")
    void defaultStatusClassifierTreatsGenuine200AsSuccess() {
        JsonNode body = Json.mapper().createObjectNode().put("id", 7);
        EnvelopeWiringAccessor accessor = new EnvelopeWiringAccessor(new StatusOnlyClassifier());

        List<ExploredPath> paths = accessor.callBuildPaths(outcomeOf(200, body), EP);

        ExploredPath p = paths.get(0);
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(p.expectedStatus()).isEqualTo(200);
    }

    /** package-private buildPaths + classifier 주입 생성자를 테스트에서 호출하기 위한 동일 패키지 래퍼. */
    static class EnvelopeWiringAccessor extends EndpointExplorationRunner {

        EnvelopeWiringAccessor(ResponseClassifier classifier) {
            super(/* sut */ null, /* connection */ (Connection) null, /* dbType */ null,
                    /* coverage */ null, /* analyzer */ null, /* budgetRequests */ 0,
                    /* httpCapture */ null, /* responseDtoFieldSets */ List.of(),
                    /* literalCandidates */ List.of(),
                    /* authProvider */ null, /* authConfig */ null,
                    /* enumConstants */ java.util.Map.of(),
                    /* enumColumns */ java.util.Map.of(),
                    /* extraHeaders */ null,
                    /* sqlCapture */ null,
                    /* kafkaCapture */ null,
                    classifier);
        }

        List<ExploredPath> callBuildPaths(ExplorationOutcome outcome, Endpoint endpoint) {
            return buildPaths(outcome, endpoint, List.of()).paths();
        }
    }
}
