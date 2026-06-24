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

        // single: 비-primary 루트 단독 빌드에서 q > 41 비교가 추출돼야 한다
        assertFalse(single.isEmpty(), "단일 루트에서 q>41 비교가 잡혀야");
        boolean singleHasQ41 = single.stream().anyMatch(
                cmp -> cmp.literal() == 41L && cmp.fieldRef().equals("q"));
        assertTrue(singleHasQ41,
                "single-root 파싱에서 literal=41, fieldRef=q 인 비교가 있어야 (q>41 가드) — "
                + "actual: " + single);

        // multi: REQ-014 핵심 — 멀티 루트 빌드에서도 동일 비교가 유지돼야 한다
        boolean multiHasQ41 = multi.stream().anyMatch(
                cmp -> cmp.literal() == 41L && cmp.fieldRef().equals("q"));
        assertTrue(multiHasQ41,
                "multi-root 파싱에서 literal=41, fieldRef=q 인 비교가 있어야 — "
                + "비-primary 루트의 제약이 누락됐을 수 있음. actual: " + multi);

        // 부수 검증: 멀티 루트가 단일 루트 비교를 버리지 않았을 것
        assertTrue(multi.size() >= single.size(),
                "멀티 루트 비교 수(" + multi.size() + ")가 단일 루트(" + single.size() + ")보다 작으면 안 됨");
    }
}
