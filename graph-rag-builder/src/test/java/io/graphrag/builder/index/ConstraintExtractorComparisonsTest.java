package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;
import io.graphrag.builder.index.ConstraintExtractor.StringEquality;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ConstraintExtractorComparisonsTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extractComparisons_resolvesFieldRefOpLiteral_andFlipsLiteralOnLeft() {
        List<Comparison> all = new ConstraintExtractor().extractComparisons(SAMPLE_SRC);

        // 컨트롤러 계층 (BoundsController.handle)
        assertThat(all).filteredOn(c -> c.classFqn().endsWith("BoundsController"))
                .extracting(Comparison::fieldRef, Comparison::op, Comparison::literal)
                .containsExactlyInAnyOrder(
                        tuple("amount", ">", 100L),
                        tuple("score", "<=", 50L),
                        tuple("amount", "==", 7L));
    }

    @Test
    void extractComparisons_coversAllLayers_notJustControllers() {
        List<Comparison> all = new ConstraintExtractor().extractComparisons(SAMPLE_SRC);

        // 서비스 계층 (BoundsService.classify) — 전 계층 추출 확인
        assertThat(all).filteredOn(c -> c.classFqn().endsWith("BoundsService"))
                .extracting(Comparison::method, Comparison::fieldRef, Comparison::op, Comparison::literal)
                .containsExactly(tuple("classify", "quantity", ">=", 5L));

        // 컨트롤러의 handler 본문 비교식도 함께 (OrderController.create: amount <= 0)
        assertThat(all).filteredOn(c -> c.classFqn().endsWith("OrderController"))
                .extracting(Comparison::fieldRef, Comparison::op, Comparison::literal)
                .contains(tuple("amount", "<=", 0L));

        // 모든 비교식은 발생 위치로 태깅된다
        assertThat(all).allMatch(c -> c.classFqn() != null && c.method() != null && c.line() > 0);
    }

    @Test
    void extractStringEqualities_coversBothDirectionsAndAllLayers() {
        List<StringEquality> all = new ConstraintExtractor().extractStringEqualities(SAMPLE_SRC);

        assertThat(all).extracting(StringEquality::fieldRef, StringEquality::value)
                .contains(
                        tuple("label", "VIP"),     // 컨트롤러, 리터럴 좌변 "VIP".equals(label)
                        tuple("mode", "BULK"),     // 서비스 계층 mode.equals("BULK")
                        tuple("type", "EXPRESS")); // OrderController "EXPRESS".equals(request.type())
        assertThat(all).allMatch(s -> s.classFqn() != null && s.method() != null && s.line() > 0);
    }
}
