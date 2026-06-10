package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔진 2: coverage-guided 변이 (docs/05 Phase B).
 * 새 분기를 연 입력을 시드 큐에 넣고 다시 변이한다 (2단 이상 조합 도달).
 * 포화 감지: 연속 saturationLimit회 novelty 없음 → 조기 종료.
 */
public class CoverageGuidedFuzzer implements PathExplorer {

    private final int saturationLimit;

    public CoverageGuidedFuzzer(int saturationLimit) {
        this.saturationLimit = saturationLimit;
    }

    @Override
    public String name() {
        return "fuzzer";
    }

    @Override
    public ExplorationResult explore(EndpointTarget target, ExplorationBudget budget,
                                     KnownCoverage known) {
        List<ExplorationResult.ExploredInput> inputs = new ArrayList<>();
        List<JsonNode> queue = new ArrayList<>(known.seeds());
        List<InputMutator.Mutation> mutations = InputMutator.firstOrder(target.shape());
        int dry = 0;

        for (int seedIndex = 0; seedIndex < queue.size(); seedIndex++) {
            for (InputMutator.Mutation mutation : mutations) {
                if (dry >= saturationLimit) {
                    return new ExplorationResult(inputs);   // coverage saturation
                }
                ObjectNode body = mutation.apply().apply(InputMutator.copy(queue.get(seedIndex)));
                if (!known.markTried(body)) {
                    continue;   // 이미 시도한 입력 — 예산 미소비
                }
                if (!budget.tryConsume()) {
                    return new ExplorationResult(inputs);   // budget exhaust
                }
                InvocationOutcome outcome = target.invoker().invoke(body);
                inputs.add(new ExplorationResult.ExploredInput(body, outcome));
                if (known.isNovel(outcome.coveredBranches())) {
                    known.merge(outcome.coveredBranches());
                    known.addSeed(body);
                    queue.add(body);
                    dry = 0;
                } else {
                    dry++;
                }
            }
        }
        return new ExplorationResult(inputs);
    }
}
