package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MultiRootConstraintTest {
    private Path svc(Path root, String pkg, String cls, String body) throws Exception {
        Path dir = Files.createDirectories(root.resolve(pkg.replace('.', '/')));
        Files.writeString(dir.resolve(cls + ".java"),
            "package " + pkg + ";\npublic class " + cls + " {\n" + body + "\n}");
        return dir;
    }

    @Test
    void nonPrimaryHandlerConstraintsEquivalent(@TempDir Path tmp) throws Exception {
        Path primary = tmp.resolve("feature");
        Path nonPrimary = tmp.resolve("common");
        svc(primary, "f", "F", "public void a(){}");
        svc(nonPrimary, "c", "C", "public void g(int q){ if (q > 41) {} }");

        ConstraintExtractor ex = new ConstraintExtractor();
        List<ConstraintExtractor.Comparison> single =
                ex.extractComparisons(SourceRoots.single(nonPrimary));
        List<ConstraintExtractor.Comparison> multi =
                ex.extractComparisons(SourceRoots.of(List.of(primary, nonPrimary), primary));

        assertFalse(single.isEmpty(), "단일 루트에서 q>41 비교가 잡혀야");
        assertTrue(multi.size() >= single.size(), "멀티 루트가 비-primary 비교를 누락하지 않아야");
    }
}
