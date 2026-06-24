package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import spoon.reflect.CtModel;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedSpoonModelMultiRootTest {
    @Test
    void buildsFromAllRoots(@TempDir Path tmp) throws Exception {
        Path ra = Files.createDirectories(tmp.resolve("ra/p"));
        Path rb = Files.createDirectories(tmp.resolve("rb/q"));
        Files.writeString(ra.resolve("A.java"), "package p; public class A {}");
        Files.writeString(rb.resolve("B.java"), "package q; public class B {}");
        CtModel model = SharedSpoonModel.build(SourceRoots.of(List.of(tmp.resolve("ra"), tmp.resolve("rb")), tmp.resolve("ra")));
        List<String> types = model.getAllTypes().stream().map(t -> t.getQualifiedName()).toList();
        assertTrue(types.contains("p.A"));
        assertTrue(types.contains("q.B"));
    }
}
