package io.graphrag.builder.exploration;

import io.graphrag.model.Endpoint;
import io.graphrag.model.SampleInput;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Coverage-guided fuzzer (Phase 1 stretch).
 *
 * <p>seed input들로 시작해 mutation으로 새 input 생성. 사용자가 제공한 score 함수가
 * input에 대한 {@link CoverageSignature}를 반환. 새 signature를 갖는 input만 유지.
 *
 * <p>실 JaCoCo runtime 부착 + signature 도출은 호출자가 score 함수 안에서 구현. 본 클래스는
 * 입력 진화 + novelty 추적의 generic 엔진.
 */
public final class CoverageGuidedFuzzer implements PathExplorer {

    private final List<SampleInput> seeds;
    private final Function<SampleInput, CoverageSignature> scorer;
    private final long randomSeed;

    public CoverageGuidedFuzzer(
            List<SampleInput> seeds,
            Function<SampleInput, CoverageSignature> scorer) {
        this(seeds, scorer, 42L);
    }

    public CoverageGuidedFuzzer(
            List<SampleInput> seeds,
            Function<SampleInput, CoverageSignature> scorer,
            long randomSeed) {
        this.seeds = List.copyOf(seeds);
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.randomSeed = randomSeed;
    }

    @Override
    public String name() { return "fuzzer"; }

    @Override
    public List<SampleInput> proposeInputs(Endpoint endpoint, ExplorationBudget budget) {
        Random rng = new Random(randomSeed);
        Set<CoverageSignature> seenSignatures = new HashSet<>();
        List<SampleInput> retained = new ArrayList<>();

        // seed pass — 항상 first
        for (SampleInput seed : seeds) {
            if (retained.size() >= budget.maxInputs()) break;
            CoverageSignature sig = scorer.apply(seed);
            if (seenSignatures.add(sig)) {
                retained.add(seed);
            }
        }

        // mutation pass
        long startMs = System.currentTimeMillis();
        long deadlineMs = startMs + budget.timeLimit().toMillis();
        int iterations = 0;
        while (retained.size() < budget.maxInputs()
                && System.currentTimeMillis() < deadlineMs
                && iterations < budget.maxInputs() * 20) {
            iterations++;
            if (retained.isEmpty()) break;
            SampleInput base = retained.get(rng.nextInt(retained.size()));
            SampleInput mutated = mutate(base, rng);
            CoverageSignature sig = scorer.apply(mutated);
            if (seenSignatures.add(sig)) {
                retained.add(mutated);
            }
        }
        return retained;
    }

    private SampleInput mutate(SampleInput base, Random rng) {
        Object body = base.body();
        if (body instanceof Map<?, ?> srcMap) {
            Map<String, Object> mutated = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : srcMap.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Number n) {
                    int delta = rng.nextInt(7) - 3;
                    mutated.put(String.valueOf(entry.getKey()), n.intValue() + delta);
                } else if (v instanceof String s) {
                    mutated.put(String.valueOf(entry.getKey()),
                            rng.nextBoolean() ? s + "-m" : s);
                } else {
                    mutated.put(String.valueOf(entry.getKey()), v);
                }
            }
            return new SampleInput(base.headers(), base.pathParams(),
                    base.queryParams(), mutated);
        }
        // 비-Map body는 그대로
        return base;
    }

    /** {@code Duration.ofMillis(50)} 같은 짧은 budget을 위한 helper. */
    public static ExplorationBudget budget(int maxInputs, long millis) {
        return new ExplorationBudget(maxInputs, Duration.ofMillis(millis));
    }
}
