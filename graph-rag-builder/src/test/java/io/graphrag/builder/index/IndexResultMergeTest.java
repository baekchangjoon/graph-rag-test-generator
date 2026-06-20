package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class IndexResultMergeTest {
    @Test
    void merge_concatsEndpointsAndUnionsMaps() {  // REQ-004
        Endpoint a = new Endpoint("a", "GET", "/a", "C", "m", List.of(), false);
        Endpoint b = new Endpoint("b", "POST", "/b", "C", "m", List.of(), false);
        IndexResult left = new IndexResult(List.of(a), Map.of("S1", BodyShape.empty()), Set.of("a"));
        IndexResult right = new IndexResult(List.of(b), Map.of("S2", BodyShape.empty()), Set.of());

        IndexResult merged = left.merge(right);

        assertThat(merged.endpoints()).extracting(Endpoint::id).containsExactlyInAnyOrder("a", "b");
        assertThat(merged.bodyShapes()).containsKeys("S1", "S2");
        assertThat(merged.validBodyEndpointIds()).containsExactly("a");
    }
}
