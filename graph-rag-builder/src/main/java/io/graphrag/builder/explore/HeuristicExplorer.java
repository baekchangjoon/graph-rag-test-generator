package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** 엔진 1: happy 입력 + 1단 boundary-value 변형 (docs/05 Phase B의 heuristic 생성기). */
public class HeuristicExplorer implements PathExplorer {

    @Override
    public String name() {
        return "heuristic";
    }

    @Override
    public ExplorationResult explore(EndpointTarget target, ExplorationBudget budget,
                                     KnownCoverage known) {
        List<ExplorationResult.ExploredInput> inputs = new ArrayList<>();
        JsonNode base = target.baseInput().deepCopy();

        tryInput(base, target, budget, known, inputs);            // happy ALWAYS runs (array or object)
        if (base instanceof ObjectNode objBase) {                 // mutations only for object bodies
            for (InputMutator.Mutation mutation : InputMutator.forTarget(target)) {
                tryInput(mutation.apply().apply(InputMutator.copy(objBase)), target, budget, known, inputs);
            }
        }
        return new ExplorationResult(inputs);
    }

    private void tryInput(JsonNode body, EndpointTarget target, ExplorationBudget budget,
                          KnownCoverage known, List<ExplorationResult.ExploredInput> inputs) {
        if (!known.markTried(body) || !budget.tryConsume()) {
            return;
        }
        InvocationOutcome outcome = target.invoker().invoke(body);
        inputs.add(new ExplorationResult.ExploredInput(body, outcome));
        if (known.isNovel(outcome.coveredBranches())) {
            known.merge(outcome.coveredBranches());
            known.addSeed(body, outcome.status());
        }
    }
}
