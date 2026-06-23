package io.graphrag.builder.store;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void callSitesRoundTripIncludingPresentAndEmptyShape() throws Exception {
        BodyShape invShape = new BodyShape("InventoryResponse",
                List.of(new BodyShape.BodyField("available", "Integer")), false);
        StaticIndex si = new StaticIndex(
                new IndexResult(List.of(), Map.of(), Set.of(), Map.of()),
                new WsIndexResult(List.of(), Map.of()),
                new KafkaIndexResult(List.of(), Map.of()),
                List.of(), List.of(), Map.of(),
                List.of(
                        new ExternalCallSite("GET", "/inventory/stock", Optional.of(invShape)),
                        new ExternalCallSite("POST", "/pay", Optional.empty())));

        String json = Json.mapper().writeValueAsString(si);
        StaticIndex back = Json.mapper().readValue(json, StaticIndex.class);

        assertThat(back.callSites()).hasSize(2);
        ExternalCallSite inv = back.callSites().get(0);
        assertThat(inv.httpMethod()).isEqualTo("GET");
        assertThat(inv.pathLiteral()).isEqualTo("/inventory/stock");
        assertThat(inv.responseShape()).isPresent();
        assertThat(inv.responseShape().get().fields()).hasSize(1);
        assertThat(back.callSites().get(1).responseShape()).isEmpty();
    }

    @Test
    void manifestRoundTrips() throws Exception {
        IndexManifest m = new IndexManifest(2, "loginPath=/login;publicPaths=/health",
                Map.of("a/Foo.java", new IndexManifest.FileEntry("sutSrc", "h1")));
        String json = Json.mapper().writeValueAsString(m);
        IndexManifest back = Json.mapper().readValue(json, IndexManifest.class);
        assertThat(back.schemaVersion()).isEqualTo(2);
        assertThat(back.authFingerprint()).isEqualTo("loginPath=/login;publicPaths=/health");
        assertThat(back.files().get("a/Foo.java").hash()).isEqualTo("h1");
    }
}
