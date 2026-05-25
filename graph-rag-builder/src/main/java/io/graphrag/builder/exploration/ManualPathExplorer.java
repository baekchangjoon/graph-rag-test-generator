package io.graphrag.builder.exploration;

import io.graphrag.model.Endpoint;
import io.graphrag.model.SampleInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 명시한 입력 세트를 그대로 제안하는 단순 탐색 엔진.
 *
 * <p>Phase 1.0: 사람이 endpoint별로 의미 있는 시나리오 입력을 정의.
 * Phase 1.2의 CoverageGuidedFuzzer가 이를 자동화.
 */
public final class ManualPathExplorer implements PathExplorer {

    private final List<SampleInput> seeds;

    public ManualPathExplorer(List<SampleInput> seeds) {
        this.seeds = List.copyOf(seeds);
    }

    /**
     * 편의 팩토리: body Map 리스트로부터 SampleInput 리스트 생성.
     */
    public static ManualPathExplorer fromBodies(List<Map<String, Object>> bodies) {
        List<SampleInput> seeds = new ArrayList<>(bodies.size());
        for (Map<String, Object> body : bodies) {
            seeds.add(new SampleInput(Map.of(), Map.of(), Map.of(), body));
        }
        return new ManualPathExplorer(seeds);
    }

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget) {
        int cap = Math.min(seeds.size(), budget.maxInputs());
        return List.copyOf(seeds.subList(0, cap));
    }
}
