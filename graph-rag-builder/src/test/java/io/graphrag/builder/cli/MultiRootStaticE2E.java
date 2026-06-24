package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MultiRootStaticE2E {

    /**
     * Locates e2e/src/multiroot robustly regardless of whether Gradle sets user.dir to the
     * module dir (graph-rag-builder/) or the repo root.
     * <p>
     * Strategy: walk up from user.dir until a directory that contains e2e/src/multiroot is found.
     */
    private static final Path BASE = findMultirootBase();

    private static Path findMultirootBase() {
        Path dir = Path.of(System.getProperty("user.dir"));
        // Walk up at most 4 levels to find the repo root containing e2e/src/multiroot
        for (int i = 0; i <= 4; i++) {
            Path candidate = dir.resolve("e2e").resolve("src").resolve("multiroot");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException(
                "Cannot locate e2e/src/multiroot from user.dir=" + System.getProperty("user.dir"));
    }

    private Set<String> pathsOf(String sutSrcArg) {
        SourceRoots roots = SutSrcResolver.resolve(sutSrcArg, null);
        return BuilderCli.indexStatically(roots, List.of(), null).index().endpoints()
                .stream().map(e -> e.path()).collect(Collectors.toSet());
    }

    @Test
    void selectedRootsOnly() {
        Set<String> p = pathsOf(BASE + "/{feature,common}");
        assertEquals(Set.of("/api/feature", "/api/common"), p);   // REQ-001
    }

    @Test
    void braceEqualsCommaList() {
        assertEquals(pathsOf(BASE + "/{feature,common}"),
                     pathsOf(BASE + "/feature, " + BASE + "/common"));   // REQ-002
    }

    @Test
    void braceEqualsUnionOfSeparateBuilds() {
        Set<String> union = new HashSet<>(pathsOf(BASE + "/feature"));
        union.addAll(pathsOf(BASE + "/common"));
        assertEquals(union, pathsOf(BASE + "/{feature,common}"));   // REQ-002 (단독 빌드 합집합)
    }

    @Test
    void mixLiteralAndGlob() {
        Set<String> p = pathsOf(BASE + "/feature, " + BASE + "/common/**");
        assertTrue(p.contains("/api/feature"));
        assertTrue(p.contains("/api/common"));
        assertFalse(p.contains("/api/other"));   // REQ-003
    }

    @Test
    void sutSrcIntersectEndpoint() {
        SourceRoots roots = SutSrcResolver.resolve(BASE + "/{feature,common}", null);
        var bundle = BuilderCli.indexStatically(roots, List.of(), null);
        Set<String> ids = EndpointSelector.resolve(List.of("GET **"),
                bundle.index().endpoints(), bundle.ws().endpoints(), bundle.kafka().consumers());
        // feature+common 중 GET 만 → get-api-common
        assertEquals(1, ids.size());   // REQ-010
        assertTrue(ids.iterator().next().contains("common"));
    }
}
