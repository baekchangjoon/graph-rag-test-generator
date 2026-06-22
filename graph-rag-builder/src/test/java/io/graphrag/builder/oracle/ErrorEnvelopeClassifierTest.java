package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Outcome;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorEnvelopeClassifierTest {
    private final ResponseClassifier c = new ErrorEnvelopeClassifier(List.of("errorCode"), "errorCode");

    @Test void enveloped200IsFailureAndRecoversStatus() {
        ObjectNode b = Json.mapper().createObjectNode();
        b.put("errorCode", "404"); b.put("errorDetail", "...BizException...");
        Outcome o = c.classify(200, b);
        assertThat(o.kind()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(o.semanticStatusText()).isEqualTo("404");
        assertThat(o.semanticStatus()).isEqualTo(404);
    }

    @Test void successFieldNullStaysSuccess() {
        ObjectNode b = Json.mapper().createObjectNode(); b.putNull("errorCode");
        assertThat(c.classify(200, b).kind()).isEqualTo(Outcome.Kind.SUCCESS);
    }

    @Test void absentTriggerStaysSuccess() {
        assertThat(c.classify(200, Json.mapper().createObjectNode()).kind()).isEqualTo(Outcome.Kind.SUCCESS);
    }

    @Test void unparseableStatusKeepsWireStatus() {
        ObjectNode b = Json.mapper().createObjectNode(); b.put("errorCode", "X");
        Outcome o = c.classify(200, b);
        assertThat(o.kind()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(o.semanticStatus()).isEqualTo(200);
        assertThat(o.semanticStatusText()).isEqualTo("X");
    }
}
