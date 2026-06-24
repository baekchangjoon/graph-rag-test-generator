package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SutSrcResolverTest {

    private Path mkdirs(Path base, String rel) throws Exception {
        Path p = base.resolve(rel);
        Files.createDirectories(p);
        return p;
    }

    @Test
    void braceSelectsSiblingsExcludesOthers(@TempDir Path tmp) throws Exception {
        Path feature = mkdirs(tmp, "a/b/c/feature");
        Path common = mkdirs(tmp, "a/b/c/common");
        mkdirs(tmp, "a/b/c/other");
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/b/c/{feature,common}", null);
        assertEquals(List.of(common.toRealPath(), feature.toRealPath()),
                r.parseRoots().stream().sorted().toList());
    }

    @Test
    void mixLiteralAndGlob(@TempDir Path tmp) throws Exception {
        Path orders = mkdirs(tmp, "a/orders");
        Path commonSub = mkdirs(tmp, "a/common/sub");
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/orders, " + tmp + "/a/common/**", null);
        assertTrue(r.parseRoots().contains(orders.toRealPath()));
        assertTrue(r.parseRoots().contains(commonSub.toRealPath()));
    }

    @Test
    void zeroMatchFailsFastNamingPattern(@TempDir Path tmp) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SutSrcResolver.resolve(tmp + "/ghost/**", null));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void dedupAndStableOrder(@TempDir Path tmp) throws Exception {
        Path feature = mkdirs(tmp, "a/feature");
        // 같은 디렉터리를 두 패턴이 매칭
        SourceRoots r = SutSrcResolver.resolve(tmp + "/a/feature, " + tmp + "/a/*", null);
        Path featureReal = feature.toRealPath();
        long featureCount = r.parseRoots().stream().filter(p -> p.equals(featureReal)).count();
        assertEquals(1, featureCount);
    }

    @Test
    void starDoesNotRecurseDoubleStarDoes(@TempDir Path tmp) throws Exception {
        mkdirs(tmp, "a/x");
        Path deep = mkdirs(tmp, "a/x/deep");
        SourceRoots shallow = SutSrcResolver.resolve(tmp + "/a/*", null);
        assertFalse(shallow.parseRoots().contains(deep.toRealPath()));
        SourceRoots recursive = SutSrcResolver.resolve(tmp + "/a/**", null);
        assertTrue(recursive.parseRoots().contains(deep.toRealPath()));
    }

    @Test
    void malformedGlobWrapped(@TempDir Path tmp) throws Exception {
        mkdirs(tmp, "a");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SutSrcResolver.resolve(tmp + "/a/[", null));   // 불균형 bracket
        assertTrue(ex.getMessage().contains("malformed") || ex.getMessage().contains("["));
    }

    @Test
    void resourceDirsFallbackPerRoot(@TempDir Path tmp) throws Exception {
        Path featureSrc = mkdirs(tmp, "feature/src/main/java");
        Path featureRes = mkdirs(tmp, "feature/src/main/resources");
        SourceRoots r = SourceRoots.single(featureSrc);
        assertEquals(List.of(featureRes), SutSrcResolver.resourceDirs(r, null));
    }

    @Test
    void resourceDirsScansAllRootsWhenNotExplicit(@TempDir Path tmp) throws Exception {
        Path fSrc = mkdirs(tmp, "feature/src/main/java");
        Path fRes = mkdirs(tmp, "feature/src/main/resources");
        Path cSrc = mkdirs(tmp, "common/src/main/java");
        Path cRes = mkdirs(tmp, "common/src/main/resources");
        SourceRoots r = SourceRoots.of(List.of(fSrc, cSrc), fSrc);
        assertEquals(List.of(fRes, cRes), SutSrcResolver.resourceDirs(r, null));  // REQ-019
    }
}
