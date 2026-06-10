package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.run.SampleInputSynthesizer;

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
        ObjectNode base = new SampleInputSynthesizer()
                .synthesize(target.shape(), target.tables()).body();

        tryInput(base, target, budget, known, inputs);
        for (InputMutator.Mutation mutation : InputMutator.firstOrder(target.shape())) {
            tryInput(mutation.apply().apply(InputMutator.copy(base)), target, budget, known, inputs);
        }
        return new ExplorationResult(inputs);
    }

    private void tryInput(ObjectNode body, EndpointTarget target, ExplorationBudget budget,
                          KnownCoverage known, List<ExplorationResult.ExploredInput> inputs) {
        if (!known.markTried(body) || !budget.tryConsume()) {
            return;
        }
        InvocationOutcome outcome = target.invoker().invoke(body);
        inputs.add(new ExplorationResult.ExploredInput(body, outcome));
        if (known.isNovel(outcome.coveredBranches())) {
            known.merge(outcome.coveredBranches());
            known.addSeed(body);
        }
    }
}
