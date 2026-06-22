package io.graphrag.builder.oracle;

import io.graphrag.model.Outcome;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusOnlyClassifierTest {
    private final ResponseClassifier c = new StatusOnlyClassifier();

    @Test void twoxxIsSuccess() {
        Outcome o = c.classify(200, Json.mapper().createObjectNode());
        assertThat(o.kind()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(o.semanticStatus()).isEqualTo(200);
    }
    @Test void nonTwoxxIsFailure() {
        assertThat(c.classify(404, null).kind()).isEqualTo(Outcome.Kind.FAILURE);
    }
}
