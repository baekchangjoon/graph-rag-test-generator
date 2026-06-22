package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCacheTest {

    private static StaticIndex empty() {
        return new StaticIndex(new IndexResult(List.of(), Map.of(), Set.of(), Map.of()),
                new WsIndexResult(List.of(), Map.of()), new KafkaIndexResult(List.of(), Map.of()),
                List.of(), List.of(), Map.of());
    }

    @Test
    void scanDetectsContentChange() throws Exception {
        Path src = Files.createTempDirectory("src");
        Path res = Files.createTempDirectory("res");
        Files.writeString(src.resolve("A.java"), "class A {}");
        IndexManifest m1 = IndexCache.scan(src, res);
        Files.writeString(src.resolve("A.java"), "class A { int x; }");
        IndexManifest m2 = IndexCache.scan(src, res);
        assertThat(IndexCache.isFresh(m1, m2)).isFalse();
        assertThat(IndexCache.isFresh(m1, m1)).isTrue();
    }

    @Test
    void saveThenLoadRestoresWhenFresh() throws Exception {
        Path cache = Files.createTempDirectory("cache");
        Path src = Files.createTempDirectory("src2");
        Path res = Files.createTempDirectory("res2");
        Files.writeString(src.resolve("A.java"), "class A {}");
        IndexManifest m = IndexCache.scan(src, res);
        IndexCache.save(cache, m, empty());
        assertThat(IndexCache.load(cache, m)).isPresent();
    }

    @Test
    void schemaMismatchTriggersRebuild() throws Exception {       // REQ-008
        Path cache = Files.createTempDirectory("cache3");
        IndexManifest stale = new IndexManifest(IndexCache.SCHEMA_VERSION - 1, Map.of());
        IndexCache.save(cache, stale, empty());
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, Map.of())))
                .isEmpty();
    }

    @Test
    void corruptManifestFallsBackToRebuild() throws Exception {   // REQ-010
        Path cache = Files.createTempDirectory("cache4");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("manifest.json"), "{ not json");
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, Map.of())))
                .isEmpty();
    }
}
