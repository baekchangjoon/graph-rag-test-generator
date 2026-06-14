package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ConstraintExtractorComparisonsTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extractComparisons_resolvesFieldRefOpLiteral_andFlipsLiteralOnLeft() {
        List<Comparison> comparisons = new ConstraintExtractor().extractComparisons(
                SAMPLE_SRC, "io.graphrag.sample.bounds.BoundsController", "handle");

        assertThat(comparisons)
                .extracting(Comparison::fieldRef, Comparison::op, Comparison::literal)
                .containsExactlyInAnyOrder(
                        tuple("amount", ">", 100L),
                        tuple("score", "<=", 50L),
                        tuple("amount", "==", 7L));
        assertThat(comparisons).allMatch(c -> c.line() > 0);
    }

    @Test
    void extractComparisons_unknownMethod_returnsEmpty() {
        assertThat(new ConstraintExtractor().extractComparisons(
                SAMPLE_SRC, "io.graphrag.sample.bounds.BoundsController", "nope")).isEmpty();
    }
}
