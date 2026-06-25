package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExplorationOrchestrator tie-break 검증: 같은 coverageKey를 반환하는 stub PathExplorer 2개로
 * candidates.merge()의 null → non-null 교체 로직을 검증한다.
 *
 * REQ-002, REQ-008
 */
class ExplorationOrchestratorTraceTest {

    private static final BranchRef BR = new BranchRef("C", "m", 10, 0);
    private static final String COVERAGE_KEY = "same-coverage-key";

    /** 두 probe 모두 non-null traceId(t1, t2) → 첫 proto가 생존(t1). */
    @Test
    void representativeIsSurvivingProbe() {
        JsonNode body = Json.mapper().createObjectNode().put("x", 1);

        InvocationOutcome outcomeT1 = outcomeWithTrace(COVERAGE_KEY, "t1");
        InvocationOutcome outcomeT2 = outcomeWithTrace(COVERAGE_KEY, "t2");

        PathExplorer engine1 = stubExplorer("e1", body, outcomeT1);
        PathExplorer engine2 = stubExplorer("e2", body, outcomeT2);

        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(engine1, engine2), 10);

        EndpointTarget target = minimalTarget();
        List<PathCandidate> paths = orchestrator.explore(target).paths();

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).coverageTraceId()).isEqualTo("t1");
    }

    /** 첫 proto traceId=null, 두 번째 non-null(t3) → tie-break로 t3 채택. */
    @Test
    void nullTraceIdYieldsEmptyList() {
        JsonNode body = Json.mapper().createObjectNode().put("x", 1);

        InvocationOutcome outcomeNull = outcomeWithTrace(COVERAGE_KEY, null);
        InvocationOutcome outcomeT3   = outcomeWithTrace(COVERAGE_KEY, "t3");

        PathExplorer engine1 = stubExplorer("e1", body, outcomeNull);
        PathExplorer engine2 = stubExplorer("e2", body, outcomeT3);

        ExplorationOrchestrator orchestrator = new ExplorationOrchestrator(
                List.of(engine1, engine2), 10);

        EndpointTarget target = minimalTarget();
        List<PathCandidate> paths = orchestrator.explore(target).paths();

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).coverageTraceId()).isEqualTo("t3");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static InvocationOutcome outcomeWithTrace(String coverageKey, String traceId) {
        // 13-arg canonical constructor: status, response, coveredBranches, logStart, logEnd,
        // httpExchanges, coverageKey, capturedSql, capturedEventEmits, kafkaTraceId,
        // responseHeaders, egressCalls, coverageTraceId
        return new InvocationOutcome(
                200,
                Json.mapper().createObjectNode().put("ok", true),
                Set.of(BR),
                0L, 0L,
                List.of(),
                coverageKey,
                List.of(),
                List.of(),
                null,
                java.util.Map.of(),
                List.of(),
                traceId);
    }

    private static PathExplorer stubExplorer(String name, JsonNode body, InvocationOutcome outcome) {
        return new PathExplorer() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ExplorationResult explore(EndpointTarget target, ExplorationBudget budget,
                                             KnownCoverage known) {
                budget.tryConsume();
                return new ExplorationResult(
                        List.of(new ExplorationResult.ExploredInput(body, outcome)));
            }
        };
    }

    private static EndpointTarget minimalTarget() {
        Endpoint endpoint = new Endpoint(
                "get-test", "GET", "/test", "C", "m",
                List.of(new EndpointParam("q", "String", ParamKind.QUERY)), false);
        return new EndpointTarget(
                endpoint,
                Json.mapper().createObjectNode(),
                List.of(),
                List.of(),
                body -> { throw new UnsupportedOperationException("stub does not invoke"); },
                List.of());
    }
}
