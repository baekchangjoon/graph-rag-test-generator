package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BranchRef;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 SUT를 모사한 fake invoker로 오케스트레이션을 검증한다.
 * 분기 모델: A=검증통과, B=주문성공, C=user없음, D=검증실패
 */
class ExplorationOrchestratorTest {

    private static final BranchRef A = new BranchRef("C", "create", 30, 0);
    private static final BranchRef B = new BranchRef("C", "create", 35, 0);
    private static final BranchRef C = new BranchRef("C", "create", 33, 0);
    private static final BranchRef D = new BranchRef("C", "create", 31, 0);

    private static final BodyShape SHAPE = new BodyShape("X", List.of(
            new BodyShape.BodyField("userId", "java.lang.String"),
            new BodyShape.BodyField("amount", "java.lang.Integer"),
            new BodyShape.BodyField("type", "java.lang.String")));

    private static final List<TableSchema> TABLES = List.of(
            new TableSchema("users",
                    List.of(new ColumnSchema("id", "VARCHAR", false, true),
                            new ColumnSchema("name", "VARCHAR", false, false)),
                    List.of(), List.of()),
            new TableSchema("orders", List.of(),
                    List.of(new ForeignKey("user_id", "users", "id")), List.of()));

    private final AtomicInteger requests = new AtomicInteger();

    /** 주문 로직 모사: amount<=0/누락 → 400(D), userId가 probe가 아니면 → 404(C), 아니면 201(B) */
    private final EndpointInvoker fakeInvoker = body -> {
        requests.incrementAndGet();
        JsonNode amount = body.get("amount");
        JsonNode userId = body.get("userId");
        JsonNode type = body.get("type");
        if (amount == null || amount.isNull() || amount.asInt() <= 0
                || userId == null || userId.isNull() || userId.asText().isBlank()
                || type == null || type.isNull() || type.asText().isBlank()) {
            return new InvocationOutcome(400, Json.mapper().nullNode(), Set.of(A, D), 0, 0);
        }
        if (!userId.asText().startsWith("probe-")) {
            return new InvocationOutcome(404, Json.mapper().nullNode(), Set.of(A, C), 0, 0);
        }
        return new InvocationOutcome(201, Json.mapper().createObjectNode().put("status", "PENDING"),
                Set.of(A, B), 0, 0);
    };

    private ExplorationOrchestrator orchestrator() {
        return new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(8)), 60);
    }

    private EndpointTarget target() {
        Endpoint endpoint = new Endpoint("post-api-orders", "POST", "/api/orders", "C", "create",
                List.of(new EndpointParam("request", "X", ParamKind.BODY)), false);
        return new EndpointTarget(endpoint, SHAPE, TABLES, fakeInvoker);
    }

    @Test
    void discoversAllThreeDistinctPaths() {
        List<PathCandidate> paths = orchestrator().explore(target()).paths();

        assertThat(paths).extracting(PathCandidate::status)
                .containsExactlyInAnyOrder(201, 404, 400);
        // 분기 집합 기준 dedupe — 400을 만드는 입력은 다수지만 path는 1개
        assertThat(paths).hasSize(3);
    }

    @Test
    void pathIds_areDeterministicAndStatusScoped() {
        List<PathCandidate> first = orchestrator().explore(target()).paths();
        List<PathCandidate> second = orchestrator().explore(target()).paths();

        assertThat(first).extracting(PathCandidate::pathId)
                .containsExactlyElementsOf(second.stream().map(PathCandidate::pathId).toList());
        assertThat(first).extracting(PathCandidate::pathId)
                .allMatch(id -> id.startsWith("post-api-orders-s"));
    }

    @Test
    void recordsDiscoveringEngine() {
        List<PathCandidate> paths = orchestrator().explore(target()).paths();
        // happy + 1단 변형은 heuristic이 모두 발견한다
        assertThat(paths).extracting(PathCandidate::discoveredBy)
                .allMatch(engine -> engine.equals("heuristic") || engine.equals("fuzzer"));
    }

    @Test
    void respectsTotalRequestBudget() {
        requests.set(0);
        new ExplorationOrchestrator(
                List.of(new HeuristicExplorer(), new CoverageGuidedFuzzer(8)), 5)
                .explore(target());
        assertThat(requests.get()).isLessThanOrEqualTo(5);
    }

    @Test
    void reportsCumulativeCoverage() {
        ExplorationOutcome outcome = orchestrator().explore(target());
        assertThat(outcome.coveredBranches()).containsExactlyInAnyOrder(A, B, C, D);
    }
}
