package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCliArgsTest {
    @Test
    void multiRootRejectsIncremental(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Map<String,String> opts = Map.of(
                "--sut-src", a + ", " + b,
                "--incremental-base", tmp.resolve("prev").toString());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BuilderCli.buildSourceRoots(opts));
        assertTrue(ex.getMessage().contains("incremental"));
    }

    @Test
    void singleRootAllowsIncremental(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Map<String,String> opts = Map.of(
                "--sut-src", a.toString(),
                "--incremental-base", tmp.resolve("prev").toString());
        SourceRoots roots = BuilderCli.buildSourceRoots(opts);
        assertFalse(roots.isMulti());
    }
}
