package io.graphrag.builder.run;

import io.graphrag.builder.run.ResponseFieldVariantGenerator.VariantPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-008, REQ-005, REQ-006: ResponseFieldVariantGenerator — 일반화된 후보 맵 수신,
 * enum byte-동일 가드, 단일 필드 우선, budget 절단.
 */
class ResponseFieldVariantGeneratorTest {

    @Test
    void singleFieldFirstThenCartesian() {
        // 단일 필드 2개(a=A2, b=B2)가 먼저, 그 다음 2-way(a=A2,b=B2).
        var plan = new ResponseFieldVariantGenerator().generate(
                new java.util.TreeMap<>(Map.of("a", List.of("A2"), "b", List.of("B2"))), 32);
        assertThat(plan.kept().stream().map(v -> v.label()).toList())
                .containsExactly("a=A2", "b=B2", "a=A2,b=B2");
    }

    @Test
    void enumPathByteIdenticalToStage2() {
        // 단계2가 enumConstants로 만들던 입력을, 호출자가 baseline(first const) 제외 후 넘긴 형태로 재현.
        // FulfillmentMode {STANDARD(first),EXPRESS_ONLY,BACKORDER} → non-baseline {EXPRESS_ONLY,BACKORDER}.
        var plan = new ResponseFieldVariantGenerator().generate(
                new java.util.TreeMap<>(Map.of("mode", List.of("BACKORDER", "EXPRESS_ONLY"))), 32);
        assertThat(plan.kept().stream().map(v -> v.label()).toList())
                .containsExactly("mode=BACKORDER", "mode=EXPRESS_ONLY");   // 단계2 정렬과 동일
    }

    @Test
    void budgetTruncationLoud() {
        // budget=2, 3 variants(A2,A3,A4) → kept 2, dropped 1.
        var plan = new ResponseFieldVariantGenerator().generate(
                new java.util.TreeMap<>(Map.of("a", List.of("A2", "A3", "A4"))), 2);
        assertThat(plan.kept()).hasSize(2);
        assertThat(plan.dropped()).isEqualTo(1);
    }
}
