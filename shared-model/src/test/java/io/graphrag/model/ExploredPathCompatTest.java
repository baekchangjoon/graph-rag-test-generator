package io.graphrag.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathCompatTest {

    @Test
    void legacyConstructorWith200IsSuccess() {
        ExploredPath p = new ExploredPath("id", "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(), List.of(), Map.of());
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.SUCCESS);
        assertThat(p.semanticStatus()).isEqualTo(200);
    }

    @Test
    void legacyConstructorWith404IsFailure() {
        ExploredPath p = new ExploredPath("id", "ep", null, 404, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(), List.of(), Map.of());
        assertThat(p.outcome()).isEqualTo(Outcome.Kind.FAILURE);
        assertThat(p.semanticStatus()).isEqualTo(404);
    }
}
