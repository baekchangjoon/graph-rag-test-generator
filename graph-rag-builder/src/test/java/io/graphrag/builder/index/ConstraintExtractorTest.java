package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintExtractorTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_collectsBranchConditionsOfHandlerMethod() {
        List<ConstraintExtractor.ConditionSpan> conditions = new ConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.orders.OrderController", "create");

        assertThat(conditions).isNotEmpty();
        assertThat(conditions).anyMatch(c -> c.text().contains("userId() == null"));
        assertThat(conditions).allMatch(c -> c.startLine() > 0 && c.endLine() >= c.startLine());
    }

    @Test
    void extract_unknownMethod_returnsEmpty() {
        List<ConstraintExtractor.ConditionSpan> conditions = new ConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.orders.OrderController", "nope");
        assertThat(conditions).isEmpty();
    }
}
