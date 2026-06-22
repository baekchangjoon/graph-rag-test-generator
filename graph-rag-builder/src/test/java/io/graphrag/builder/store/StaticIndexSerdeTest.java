package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaticIndexSerdeTest {

    @Test
    void staticIndexRoundTrips() throws Exception {
        StaticIndex si = new StaticIndex(
                new IndexResult(List.of(), Map.of(), Set.of(), Map.of()),
                new WsIndexResult(List.of(), Map.of()),
                new KafkaIndexResult(List.of(), Map.of()),
                List.of(), List.of(Set.of("a", "b")), Map.of("p.E", List.of("X", "Y")));

        String json = Json.mapper().writeValueAsString(si);
        StaticIndex back = Json.mapper().readValue(json, StaticIndex.class);

        assertThat(back.enumConstants()).containsEntry("p.E", List.of("X", "Y"));
        assertThat(back.responseDtoFieldSets()).containsExactly(Set.of("a", "b"));
    }

    @Test
    void manifestRoundTrips() throws Exception {
        IndexManifest m = new IndexManifest(1,
                Map.of("a/Foo.java", new IndexManifest.FileEntry("sutSrc", "h1")));
        String json = Json.mapper().writeValueAsString(m);
        IndexManifest back = Json.mapper().readValue(json, IndexManifest.class);
        assertThat(back.schemaVersion()).isEqualTo(1);
        assertThat(back.files().get("a/Foo.java").hash()).isEqualTo("h1");
    }
}
