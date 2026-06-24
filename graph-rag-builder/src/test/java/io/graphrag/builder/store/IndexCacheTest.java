package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.builder.run.AuthConfig;
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
        IndexManifest m1 = IndexCache.scan(src, res, null);
        Files.writeString(src.resolve("A.java"), "class A { int x; }");
        IndexManifest m2 = IndexCache.scan(src, res, null);
        assertThat(IndexCache.isFresh(m1, m2)).isFalse();
        assertThat(IndexCache.isFresh(m1, m1)).isTrue();
    }

    @Test
    void saveThenLoadRestoresWhenFresh() throws Exception {
        Path cache = Files.createTempDirectory("cache");
        Path src = Files.createTempDirectory("src2");
        Path res = Files.createTempDirectory("res2");
        Files.writeString(src.resolve("A.java"), "class A {}");
        IndexManifest m = IndexCache.scan(src, res, null);
        IndexCache.save(cache, m, empty());
        assertThat(IndexCache.load(cache, m)).isPresent();
    }

    @Test
    void schemaMismatchTriggersRebuild() throws Exception {       // REQ-008
        Path cache = Files.createTempDirectory("cache3");
        IndexManifest stale = new IndexManifest(IndexCache.SCHEMA_VERSION - 1, "", Map.of());
        IndexCache.save(cache, stale, empty());
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, "", Map.of())))
                .isEmpty();
    }

    @Test
    void legacySchemaVersion2InvalidatedByVersion3() throws Exception {  // REQ-010: stringLiteralsByDto 추가 후 캐시 무효화
        Path cache = Files.createTempDirectory("cache-v2");
        IndexManifest legacyV2 = new IndexManifest(2, "", Map.of());
        IndexCache.save(cache, legacyV2, empty());
        // SCHEMA_VERSION이 3으로 올라갔으므로 버전 2 캐시는 무효화돼야 한다
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, "", Map.of())))
                .isEmpty();
    }

    @Test
    void corruptManifestFallsBackToRebuild() throws Exception {   // REQ-010
        Path cache = Files.createTempDirectory("cache4");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("manifest.json"), "{ not json");
        assertThat(IndexCache.load(cache, new IndexManifest(IndexCache.SCHEMA_VERSION, "", Map.of())))
                .isEmpty();
    }

    @Test
    void authChangeInvalidatesCache() throws Exception {
        Path cache = Files.createTempDirectory("cache-auth");
        Path src = Files.createTempDirectory("src-auth");
        Path res = Files.createTempDirectory("res-auth");
        Files.writeString(src.resolve("A.java"), "class A {}");

        AuthConfig authA = new AuthConfig("/login", "user", "pass", "token", "Authorization", "Bearer",
                List.of("/public", "/health"));
        AuthConfig authB = new AuthConfig("/api/login", "user", "pass", "token", "Authorization", "Bearer",
                List.of("/public"));

        IndexManifest manifestA = IndexCache.scan(src, res, authA);
        IndexCache.save(cache, manifestA, empty());

        // 동일 소스, 다른 auth → 캐시 미스
        IndexManifest manifestB = IndexCache.scan(src, res, authB);
        assertThat(IndexCache.load(cache, manifestB)).isEmpty();
    }

    @Test
    void publicPathsCommaDoesNotCollide() throws Exception {  // delimiter 충돌 방지 검증
        Path cache = Files.createTempDirectory("cache-delimiter");
        Path src = Files.createTempDirectory("src-delimiter");
        Path res = Files.createTempDirectory("res-delimiter");
        Files.writeString(src.resolve("A.java"), "class A {}");

        // 동일 loginPath, 다른 publicPaths (한 경로에 쉼표 vs 두 개의 경로)
        AuthConfig authWithCommaInPath = new AuthConfig("/login", "user", "pass", "token", "Authorization",
                "Bearer", List.of("/public,/admin"));  // 단일 경로, 쉼표 포함
        AuthConfig authWithSplitPaths = new AuthConfig("/login", "user", "pass", "token", "Authorization",
                "Bearer", List.of("/public", "/admin"));  // 두 경로

        IndexManifest manifestA = IndexCache.scan(src, res, authWithCommaInPath);
        IndexCache.save(cache, manifestA, empty());

        // 서로 다른 auth fingerprint → 캐시 미스 (충돌 없음)
        IndexManifest manifestB = IndexCache.scan(src, res, authWithSplitPaths);
        assertThat(IndexCache.load(cache, manifestB)).isEmpty();

        // fingerprint 자체도 다른지 확인
        assertThat(manifestA.authFingerprint()).isNotEqualTo(manifestB.authFingerprint());
    }
}
