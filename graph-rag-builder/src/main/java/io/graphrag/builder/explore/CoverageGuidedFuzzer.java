package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 엔진 2: coverage-guided 변이 (docs/05 Phase B).
 * - 새 분기를 연 입력을 시드 큐에 환류해 다단 조합에 도달한다
 * - 시드 큐는 2xx 우선 정렬: 성공 경로의 연장이 깊은 분기(외부 호출, 한도 초과 등)로
 *   이어질 확률이 높다
 * - 포화 감지: 요청을 소비한 시드 패스가 연속 saturationLimit회 novelty 없음 → 종료
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
        List<KnownCoverage.Seed> queue = new ArrayList<>(known.seeds());
        queue.sort(Comparator.comparing(seed -> seed.status() / 100 != 2));   // 2xx 먼저 (stable)
        List<InputMutator.Mutation> mutations = InputMutator.forTarget(target);
        int drySeedPasses = 0;

        for (int seedIndex = 0; seedIndex < queue.size(); seedIndex++) {
            boolean requested = false;
            boolean novelInSeed = false;
            for (InputMutator.Mutation mutation : mutations) {
                ObjectNode body = mutation.apply()
                        .apply(InputMutator.copy(queue.get(seedIndex).body()));
                if (!known.markTried(body)) {
                    continue;   // 이미 시도한 입력 — 예산 미소비
                }
                if (!budget.tryConsume()) {
                    return new ExplorationResult(inputs);   // budget exhaust
                }
                requested = true;
                InvocationOutcome outcome = target.invoker().invoke(body);
                inputs.add(new ExplorationResult.ExploredInput(body, outcome));
                if (known.isNovel(outcome.coveredBranches())) {
                    known.merge(outcome.coveredBranches());
                    known.addSeed(body, outcome.status());
                    queue.add(new KnownCoverage.Seed(body, outcome.status()));
                    novelInSeed = true;
                }
            }
            if (requested) {
                drySeedPasses = novelInSeed ? 0 : drySeedPasses + 1;
                if (drySeedPasses >= saturationLimit) {
                    return new ExplorationResult(inputs);   // coverage saturation
                }
            }
        }
        return new ExplorationResult(inputs);
    }
}
