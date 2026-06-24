package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BuildConfigSourceRootsTest {
    @Test
    void defaultsToSingleFromSutSrc() {
        BuildConfig c = new BuildConfig(Path.of("/a/src"), Path.of("/a/res"), Path.of("/a.jar"),
                Path.of("/out"), "sut", "sha", null, 60, null, null, java.util.Map.of());
        assertEquals(SourceRoots.single(Path.of("/a/src")), c.sourceRoots());
        assertEquals(Path.of("/a/src"), c.sourceRoots().primary());
    }
}
