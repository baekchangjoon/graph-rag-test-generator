package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExploredPathCopyPreservationTest {

    @Test
    void withSeedIdsPreservesCoverageTraceIds() {
        ExploredPath p = new ExploredPath("id", "ep", null, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("tid-x"));
        ExploredPath copied = EndpointExplorationRunner.withSeedIds(p, List.of("seed-1"));
        assertThat(copied.coverageTraceIds()).containsExactly("tid-x");
        assertThat(copied.outcome()).isEqualTo(p.outcome());
        assertThat(copied.semanticStatus()).isEqualTo(p.semanticStatus());
    }

    @Test
    void pkRewritePreservesCoverageTraceIds() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("id", 1L);
        ExploredPath np = new ExploredPath("id", "ep", body, 200, null,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("tid-y"));
        ObjectNode nb = body.deepCopy();
        nb.put("id", "42");
        ExploredPath rewritten = EndpointExplorationRunner.rewriteBody(np, nb);
        assertThat(rewritten.coverageTraceIds()).containsExactly("tid-y");
        assertThat(rewritten.outcome()).isEqualTo(np.outcome());
        assertThat(rewritten.semanticStatus()).isEqualTo(np.semanticStatus());
    }

    /** 3-arg rewriteBody(실제 PK-rewrite 호출부가 쓰는 경로): body+response 교체, 나머지 필드 보존. */
    @Test
    void pkRewriteWithResponsePreservesFields() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("id", 1L);
        ObjectNode resp = JsonNodeFactory.instance.objectNode();
        resp.put("id", 1L);
        ExploredPath np = new ExploredPath("id", "ep", body, 200, resp,
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Outcome.Kind.SUCCESS, 200, "200", List.of("tid-z"));
        ObjectNode nb = body.deepCopy();
        nb.put("id", "42");
        ObjectNode nr = resp.deepCopy();
        nr.put("id", 42L);
        ExploredPath rewritten = EndpointExplorationRunner.rewriteBody(np, nb, nr);
        assertThat(rewritten.sampleInput().get("id").asText()).isEqualTo("42");
        assertThat(rewritten.sampleResponse().get("id").asLong()).isEqualTo(42L);
        assertThat(rewritten.coverageTraceIds()).containsExactly("tid-z");
        assertThat(rewritten.outcome()).isEqualTo(np.outcome());
        assertThat(rewritten.semanticStatus()).isEqualTo(np.semanticStatus());
    }
}
