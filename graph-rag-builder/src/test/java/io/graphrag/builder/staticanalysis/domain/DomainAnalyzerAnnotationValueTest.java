package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAnalyzerAnnotationValueTest {

    @Test
    void capturesPrimaryAnnotationValuesAcrossSpringForms(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java/demo/Ctrl.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/owners")
                class Ctrl {
                    @GetMapping("/{ownerId}/single")
                    public String single(@PathVariable("ownerId") Integer id) { return ""; }

                    @GetMapping("/{ownerId}/value")
                    public String value(@PathVariable(value = "ownerId") Integer id) { return ""; }

                    @GetMapping("/{ownerId}/named")
                    public String named(@PathVariable(name = "ownerId") Integer id) { return ""; }

                    @GetMapping("/{id}/marker")
                    public String marker(@PathVariable Integer id) { return ""; }
                }
                """);

        AstParseResult ast = AstParser.parse(tmp.resolve("src/main/java"));
        DomainAnalysisResult domain = DomainAnalyzer.analyze(ast, "demo");

        MethodAnalysis single = methodNamed(domain, "single");
        MethodAnalysis value  = methodNamed(domain, "value");
        MethodAnalysis named  = methodNamed(domain, "named");
        MethodAnalysis marker = methodNamed(domain, "marker");

        assertThat(single.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(value.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(named.parameters().get(0).annotationValues())
                .isEqualTo(Map.of("PathVariable", "ownerId"));
        assertThat(marker.parameters().get(0).annotationValues())
                .isEmpty();
    }

    private static MethodAnalysis methodNamed(DomainAnalysisResult d, String name) {
        return d.methodAnalyses().values().stream()
                .filter(m -> name.equals(m.methodName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no MethodAnalysis named '" + name + "' in " + d.methodAnalyses().keySet()));
    }
}
