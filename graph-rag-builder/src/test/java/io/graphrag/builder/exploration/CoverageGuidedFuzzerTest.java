package io.graphrag.builder.exploration;

import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageGuidedFuzzerTest {

    private final Endpoint endpoint = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo", "C", "m", false, List.of());

    private SampleInput body(Map<String, Object> body) {
        return new SampleInput(Map.of(), Map.of(), Map.of(), body);
    }

    @Test
    void identifiesAsFuzzer() {
        assertThat(new CoverageGuidedFuzzer(List.of(), i -> new CoverageSignature("x")).name())
                .isEqualTo("fuzzer");
    }

    @Test
    void seedsRetainedIfTheyHaveDistinctCoverage() {
        // signature 함수: amount 부호(음/0/양)에 따라 다른 cov
        List<SampleInput> seeds = List.of(
                body(Map.of("amount", -1)),
                body(Map.of("amount", 0)),
                body(Map.of("amount", 100)));
        CoverageGuidedFuzzer fuzzer = new CoverageGuidedFuzzer(seeds,
                i -> {
                    Object body = ((Map<?, ?>) i.body()).get("amount");
                    int n = ((Number) body).intValue();
                    String tag = n < 0 ? "neg" : n == 0 ? "zero" : "pos";
                    return new CoverageSignature(tag);
                });

        List<SampleInput> result = fuzzer.proposeInputs(endpoint,
                new ExplorationBudget(10, Duration.ofMillis(50)));

        assertThat(result).hasSize(3);   // 세 개 모두 distinct signature
    }

    @Test
    void duplicateCoverageSignaturesAreFiltered() {
        // 모든 input이 동일 cov ("x") → 첫 seed만 유지
        List<SampleInput> seeds = List.of(
                body(Map.of("amount", 1)),
                body(Map.of("amount", 2)),
                body(Map.of("amount", 3)));
        CoverageGuidedFuzzer fuzzer = new CoverageGuidedFuzzer(seeds,
                i -> new CoverageSignature("x"));

        List<SampleInput> result = fuzzer.proposeInputs(endpoint,
                new ExplorationBudget(10, Duration.ofMillis(50)));

        assertThat(result).hasSize(1);
    }

    @Test
    void mutationExpandsCoverageBeyondSeedsWhenScorerVaries() {
        // signature = amount 값 자체 (mod 5). mutation이 새 값 생성 → 새 cov.
        List<SampleInput> seeds = List.of(body(Map.of("amount", 0)));
        CoverageGuidedFuzzer fuzzer = new CoverageGuidedFuzzer(seeds,
                i -> {
                    int n = ((Number) ((Map<?, ?>) i.body()).get("amount")).intValue();
                    return new CoverageSignature(String.valueOf(Math.floorMod(n, 5)));
                });

        Set<String> seenSignatures = new HashSet<>();
        List<SampleInput> result = fuzzer.proposeInputs(endpoint,
                new ExplorationBudget(5, Duration.ofMillis(200)));

        for (SampleInput s : result) {
            int n = ((Number) ((Map<?, ?>) s.body()).get("amount")).intValue();
            seenSignatures.add(String.valueOf(Math.floorMod(n, 5)));
        }
        // seed 1개 + mutation으로 발견된 distinct signatures > 1
        assertThat(seenSignatures.size()).isGreaterThan(1);
    }

    @Test
    void budgetRespectedEvenWithUnlimitedCoverage() {
        List<SampleInput> seeds = List.of(body(Map.of("amount", 0)));
        CoverageGuidedFuzzer fuzzer = new CoverageGuidedFuzzer(seeds,
                i -> new CoverageSignature(String.valueOf(System.nanoTime())));   // 매번 새 cov

        List<SampleInput> result = fuzzer.proposeInputs(endpoint,
                new ExplorationBudget(3, Duration.ofMillis(500)));

        assertThat(result).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void deterministicForSameSeedAndScorer() {
        List<SampleInput> seeds = List.of(body(Map.of("amount", 10)));
        CoverageGuidedFuzzer fuzzer1 = new CoverageGuidedFuzzer(seeds,
                i -> new CoverageSignature(String.valueOf(
                        ((Number) ((Map<?, ?>) i.body()).get("amount")).intValue() % 7)),
                42L);
        CoverageGuidedFuzzer fuzzer2 = new CoverageGuidedFuzzer(seeds,
                i -> new CoverageSignature(String.valueOf(
                        ((Number) ((Map<?, ?>) i.body()).get("amount")).intValue() % 7)),
                42L);

        List<SampleInput> a = fuzzer1.proposeInputs(endpoint,
                new ExplorationBudget(5, Duration.ofMillis(200)));
        List<SampleInput> b = fuzzer2.proposeInputs(endpoint,
                new ExplorationBudget(5, Duration.ofMillis(200)));

        assertThat(a).isEqualTo(b);
    }
}
