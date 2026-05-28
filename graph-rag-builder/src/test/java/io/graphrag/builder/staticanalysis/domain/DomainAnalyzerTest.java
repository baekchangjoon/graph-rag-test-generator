package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAnalyzerTest {

    @Test
    void analyzes_simple_controller_and_classifies_neighbors(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo/OwnerController.java"), """
            package demo;
            @RestController @RequestMapping("/owners")
            class OwnerController {
                @GetMapping("/{id}") Object find(@PathVariable Long id) { return null; }
                @PostMapping        Object create(@RequestBody Object body) { return null; }
            }
            """);
        Files.writeString(root.resolve("demo/OwnerService.java"), """
            package demo;
            @Service class OwnerService {}
            """);
        Files.writeString(root.resolve("demo/OwnerRepository.java"), """
            package demo;
            interface OwnerRepository extends JpaRepository<Object, Long> {}
            """);
        Files.writeString(root.resolve("demo/Owner.java"), """
            package demo;
            @Entity class Owner {}
            """);

        AstParseResult ast = AstParser.parse(root);
        DomainAnalysisResult r = DomainAnalyzer.analyze(ast, "demo-project");

        assertThat(r.endpoints()).extracting(Endpoint::id)
                .containsExactly("GET:/owners/{id}", "POST:/owners");
        assertThat(r.classRoles())
                .containsEntry("demo.OwnerController", ClassRole.CONTROLLER)
                .containsEntry("demo.OwnerService",    ClassRole.SERVICE)
                .containsEntry("demo.OwnerRepository", ClassRole.REPOSITORY)
                .containsEntry("demo.Owner",           ClassRole.DOMAIN);
        assertThat(r.endpoints()).allMatch(e -> "demo-project".equals(e.project()));
    }

    @Test
    void endpoints_sorted_by_path_then_method(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/C.java"), """
            package p;
            @RestController
            class C {
                @PostMapping("/b")  Object pb() { return null; }
                @GetMapping("/a")   Object ga() { return null; }
                @DeleteMapping("/a") Object da() { return null; }
                @GetMapping("/c")   Object gc() { return null; }
            }
            """);

        DomainAnalysisResult r = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r.endpoints()).extracting(Endpoint::id)
                .containsExactly("DELETE:/a", "GET:/a", "GET:/c", "POST:/b");
    }

    @Test
    void method_analyses_populated_for_controller_service_repository_only(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/Ctrl.java"), """
            package p;
            @RestController class Ctrl { @GetMapping("/x") Object x() { if (true) return null; return null; } }
            """);
        Files.writeString(root.resolve("p/Dom.java"), """
            package p;
            @Entity class Dom { int field; int getField() { return field; } }
            """);

        DomainAnalysisResult r = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r.methodAnalyses()).containsKey("p.Ctrl#x");
        assertThat(r.methodAnalyses()).doesNotContainKey("p.Dom#getField");
    }

    @Test
    void analyze_is_deterministic(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        Files.createDirectories(root.resolve("p"));
        Files.writeString(root.resolve("p/C.java"), """
            package p;
            @RestController class C { @GetMapping("/a") Object a() { return null; } }
            """);

        DomainAnalysisResult r1 = DomainAnalyzer.analyze(AstParser.parse(root), "p");
        DomainAnalysisResult r2 = DomainAnalyzer.analyze(AstParser.parse(root), "p");

        assertThat(r1.endpoints()).isEqualTo(r2.endpoints());
        assertThat(r1.classRoles().keySet()).containsExactlyElementsOf(r2.classRoles().keySet());
        assertThat(r1.methodAnalyses().keySet()).containsExactlyElementsOf(r2.methodAnalyses().keySet());
    }
}
