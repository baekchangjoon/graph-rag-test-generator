package io.graphrag.builder.cli;

import io.graphrag.builder.index.SourceRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class MultiRootStaticIndexTest {

    private void ctrl(Path root, String pkg, String cls, String method, String path) throws Exception {
        Path dir = Files.createDirectories(root.resolve(pkg.replace('.', '/')));
        String ann = method.equals("GET") ? "GetMapping" : "PostMapping";
        Files.writeString(dir.resolve(cls + ".java"),
            "package " + pkg + ";\n" +
            "import org.springframework.web.bind.annotation.*;\n" +
            "@RestController public class " + cls + " {\n" +
            "  @" + ann + "(\"" + path + "\") public String h() { return \"x\"; }\n}");
    }

    @Test
    void selectedRootsOnly(@TempDir Path tmp) throws Exception {
        Path feature = tmp.resolve("feature");
        Path common = tmp.resolve("common");
        Path other = tmp.resolve("other");
        ctrl(feature, "f", "FeatureController", "POST", "/api/feature");
        ctrl(common, "c", "CommonController", "GET", "/api/common");
        ctrl(other, "o", "OtherController", "GET", "/api/other");

        SourceRoots roots = SourceRoots.of(List.of(feature, common), feature);
        var bundle = BuilderCli.indexStatically(roots, List.of(), null);
        Set<String> paths = bundle.index().endpoints().stream()
                .map(e -> e.path()).collect(Collectors.toSet());
        assertTrue(paths.contains("/api/feature"));
        assertTrue(paths.contains("/api/common"));
        assertFalse(paths.contains("/api/other"));   // 제외 형제 부재(REQ-001)
    }

    @Test
    void nonPrimaryMapperIncluded(@TempDir Path tmp) throws Exception {
        Path feature = tmp.resolve("feature/java");
        ctrl(feature, "f", "FeatureController", "POST", "/api/feature");
        Path commonRes = Files.createDirectories(tmp.resolve("common/resources"));
        // 실제 MapperXmlIndexer 가 인식하는 mapper XML 형식으로 작성(구현 시 기존 샘플 mapper로 형식 확인).
        Files.writeString(commonRes.resolve("CommonMapper.xml"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
            + "<mapper namespace=\"c.CommonMapper\"><select id=\"find\" resultType=\"int\">select 1</select></mapper>");
        SourceRoots roots = SourceRoots.of(List.of(feature), feature);
        var bundle = BuilderCli.indexStatically(roots, List.of(commonRes), null);
        assertFalse(bundle.mappers().isEmpty(), "비-primary resources mapper XML 포함(REQ-019)");
    }
}
