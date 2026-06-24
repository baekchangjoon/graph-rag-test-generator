package io.graphrag.builder.store;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndexCacheMultiRootTest {
    @Test
    void nonPrimaryJavaChangeChangesManifest(@TempDir Path tmp) throws Exception {
        Path primary = Files.createDirectories(tmp.resolve("feature"));
        Path nonPrimary = Files.createDirectories(tmp.resolve("common"));
        Files.writeString(primary.resolve("F.java"), "package f; class F {}");
        Files.writeString(nonPrimary.resolve("C.java"), "package c; class C {}");
        SourceRoots roots = SourceRoots.of(List.of(primary, nonPrimary), primary);

        IndexManifest m1 = IndexCache.scan(roots, List.of(), null);
        Files.writeString(nonPrimary.resolve("C.java"), "package c; class C { int x; }");
        IndexManifest m2 = IndexCache.scan(roots, List.of(), null);

        assertFalse(IndexCache.isFresh(m1, m2));   // 비-primary .java 변경 → 캐시 miss (isFresh=false)
    }

    @Test
    void nonPrimaryMapperXmlChangeChangesManifest(@TempDir Path tmp) throws Exception {
        Path primary = Files.createDirectories(tmp.resolve("feature/java"));
        Path npRes = Files.createDirectories(tmp.resolve("common/resources"));
        Files.writeString(primary.resolve("F.java"), "package f; class F {}");
        Files.writeString(npRes.resolve("CommonMapper.xml"), "<mapper><select id=\"a\"/></mapper>");
        SourceRoots roots = SourceRoots.of(List.of(primary), primary);

        IndexManifest m1 = IndexCache.scan(roots, List.of(npRes), null);
        Files.writeString(npRes.resolve("CommonMapper.xml"), "<mapper><select id=\"a\"/><select id=\"b\"/></mapper>");
        IndexManifest m2 = IndexCache.scan(roots, List.of(npRes), null);

        assertFalse(IndexCache.isFresh(m1, m2));   // 비-primary mapper XML 변경 → 캐시 miss (REQ-013)
    }
}
