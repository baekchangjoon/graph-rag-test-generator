package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.oracle.ErrorEnvelopeClassifier;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005: 파이프라인 outcome-gating — 시드 정렬·dedup 키·path-id가 raw status가 아닌
 * 분류된 Outcome.kind 기준으로 동작하는지 검증.
 */
@DisplayName("REQ-005: 파이프라인 outcome-gating")
class OutcomeGatingTest {

    private static final BranchRef A = new BranchRef("C", "m", 10, 0);
    private static final BranchRef B = new BranchRef("C", "m", 11, 0);

    private static ObjectNode obj(String k, int v) {
        return Json.mapper().createObjectNode().put(k, v);
    }

    /** 엔진이 입력을 직접 공급하므로 baseInput/mutator는 쓰이지 않는 최소 타깃. */
    private static EndpointTarget simpleTarget(EndpointInvoker invoker) {
        Endpoint endpoint = new Endpoint("post-x", "POST", "/x", "C", "m",
                List.of(new EndpointParam("request", "X", ParamKind.BODY)), false);
        return new EndpointTarget(endpoint, Json.mapper().createObjectNode(),
                List.<BodyShape.BodyField>of(), List.of(), invoker);
    }

    @Test
    @DisplayName("REQ-005a: ErrorEnvelopeClassifier 하에서 enveloped-200 시드가 genuine-200 시드보다 뒤로 정렬")
    void seedQueueSortsSuccessFirstUnderEnvelopeClassifier() {
        ResponseClassifier classifier = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");

        // 둘 다 와이어 status 200이지만 enveloped는 FAILURE로 분류된다.
        JsonNode genuine = obj("ok", 1);
        JsonNode enveloped = Json.mapper().createObjectNode().put("errorCode", "404");
        KnownCoverage.Seed envelopedSeed =
                new KnownCoverage.Seed(obj("a", 1), 200, classifier.classify(200, enveloped).kind());
        KnownCoverage.Seed genuineSeed =
                new KnownCoverage.Seed(obj("b", 2), 200, classifier.classify(200, genuine).kind());

        // enveloped를 앞에 둔 채 fuzzer 정렬 키(kind != SUCCESS)로 정렬하면 genuine이 앞으로 와야 한다.
        List<KnownCoverage.Seed> queue = new ArrayList<>(List.of(envelopedSeed, genuineSeed));
        queue.sort(Comparator.comparing(seed -> seed.kind() != Outcome.Kind.SUCCESS));

        assertThat(queue.get(0)).isSameAs(genuineSeed);
        assertThat(queue.get(0).kind()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(queue.get(1).kind()).isEqualTo(Outcome.Kind.FAILURE);
    }

    @Test
    @DisplayName("REQ-005a: KnownCoverage.addSeed가 outcome 기반 kind를 저장")
    void addSeedStoresClassifiedKind() {
        KnownCoverage known = new KnownCoverage();
        known.addSeed(obj("a", 1), 200, Outcome.Kind.SUCCESS);
        known.addSeed(obj("b", 2), 200, Outcome.Kind.FAILURE);
        assertThat(known.seeds()).extracting(KnownCoverage.Seed::kind)
                .containsExactly(Outcome.Kind.SUCCESS, Outcome.Kind.FAILURE);
    }

    /** 와이어 status가 모두 200이지만 응답 body로 SUCCESS/FAILURE가 갈리는 fake invoker.
     *  errorCode 필드가 있으면 enveloped 에러, 없으면 진짜 성공. 둘 다 같은 분기 집합을 연다. */
    private EndpointInvoker dualOutcomeInvoker(AtomicInteger calls) {
        return body -> {
            calls.incrementAndGet();
            JsonNode trigger = body.get("trigger");
            boolean error = trigger != null && trigger.asInt() < 0;
            JsonNode resp = error
                    ? Json.mapper().createObjectNode().put("errorCode", "404")
                    : Json.mapper().createObjectNode().put("ok", 1);
            return new InvocationOutcome(200, resp, Set.of(A, B), 0, 0);
        };
    }

    @Test
    @DisplayName("REQ-005b/c: 동일 coverage라도 enveloped-200(FAILURE)와 genuine-200(SUCCESS)은 분리, FAILURE path-id에 semanticStatus 포함")
    void dedupSeparatesSuccessAndFailureAndEmbedsSemanticStatus() {
        ResponseClassifier classifier = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");
        AtomicInteger calls = new AtomicInteger();
        EndpointInvoker invoker = dualOutcomeInvoker(calls);

        // genuine + enveloped 두 입력을 같은 분기집합으로 직접 공급하는 단일 엔진.
        PathExplorer engine = new PathExplorer() {
            @Override public String name() { return "fake"; }
            @Override public ExplorationResult explore(EndpointTarget t, ExplorationBudget b, KnownCoverage k) {
                List<ExplorationResult.ExploredInput> inputs = new ArrayList<>();
                ObjectNode good = obj("trigger", 1);
                ObjectNode bad = obj("trigger", -1);
                inputs.add(new ExplorationResult.ExploredInput(good, invoker.invoke(good)));
                inputs.add(new ExplorationResult.ExploredInput(bad, invoker.invoke(bad)));
                return new ExplorationResult(inputs);
            }
        };
        EndpointTarget target = simpleTarget(invoker);

        List<PathCandidate> paths = new ExplorationOrchestrator(List.of(engine), 10, classifier)
                .explore(target).paths();

        // 동일 coverageKey(없음→분기집합)이지만 SUCCESS/FAILURE로 2개 path가 보존됨.
        assertThat(paths).hasSize(2);
        assertThat(paths).extracting(PathCandidate::status).containsOnly(200);

        PathCandidate failure = paths.stream()
                .filter(p -> p.pathId().contains("e404"))
                .findFirst().orElseThrow();
        assertThat(failure.pathId()).contains("-s200e404-");

        PathCandidate success = paths.stream()
                .filter(p -> !p.pathId().contains("e"))
                .findFirst().orElseThrow();
        assertThat(success.pathId()).contains("-s200-");
        assertThat(success.pathId()).doesNotContain("e404");
    }

    @Test
    @DisplayName("REQ-002 regression: StatusOnlyClassifier(기본)에서 genuine-200 두 입력은 동일 coverage면 1개로 dedupe (기존 동작 유지)")
    void defaultClassifierDedupesIdenticalCoverageAsBefore() {
        AtomicInteger calls = new AtomicInteger();
        EndpointInvoker invoker = body -> {
            calls.incrementAndGet();
            return new InvocationOutcome(200, Json.mapper().createObjectNode().put("ok", 1), Set.of(A, B), 0, 0);
        };
        PathExplorer engine = new PathExplorer() {
            @Override public String name() { return "fake"; }
            @Override public ExplorationResult explore(EndpointTarget t, ExplorationBudget b, KnownCoverage k) {
                List<ExplorationResult.ExploredInput> inputs = new ArrayList<>();
                ObjectNode a = obj("x", 1);
                ObjectNode b2 = obj("y", 2);
                inputs.add(new ExplorationResult.ExploredInput(a, invoker.invoke(a)));
                inputs.add(new ExplorationResult.ExploredInput(b2, invoker.invoke(b2)));
                return new ExplorationResult(inputs);
            }
        };
        EndpointTarget target = simpleTarget(invoker);

        List<PathCandidate> paths = new ExplorationOrchestrator(List.of(engine), 10, new StatusOnlyClassifier())
                .explore(target).paths();

        // 동일 status + 동일 coverage + 둘 다 SUCCESS → 기존처럼 1개로 collapse, path-id에 semanticStatus 미포함.
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).pathId()).contains("-s200-");
        assertThat(paths.get(0).pathId()).doesNotContain("e");
    }
}
