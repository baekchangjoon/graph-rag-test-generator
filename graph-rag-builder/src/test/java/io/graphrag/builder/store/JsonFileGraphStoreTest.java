package io.graphrag.builder.store;

import io.graphrag.model.Endpoint;
import io.graphrag.model.GraphAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileGraphStoreTest {

    @TempDir
    Path dir;

    @Test
    void saveAndLoad_roundTrips() {
        GraphAsset asset = new GraphAsset("order-service", "sha-1",
                List.of(new Endpoint("post-api-orders", "POST", "/api/orders",
                        "C", "m", List.of(), false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());

        JsonFileGraphStore store = new JsonFileGraphStore(dir);
        store.save(asset);

        assertThat(Files.exists(dir.resolve("graph.json"))).isTrue();
        assertThat(store.load()).isEqualTo(asset);
    }
}
