package io.graphrag.generator.verify;

import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.generator.core.TestSynthesizer;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceCompilerTest {

    @Test
    void compilesTrivialValidClass() {
        String source = """
                package gen;
                public class Hello {
                    public static String greet() { return "hi"; }
                }
                """;
        CompileResult r = JavaSourceCompiler.compile("gen.Hello", source);
        assertThat(r.success()).isTrue();
        assertThat(r.diagnostics()).isEmpty();
    }

    @Test
    void reportsErrorsForInvalidJava() {
        String source = "package gen; public class Broken { void m() { return 5 }";
        CompileResult r = JavaSourceCompiler.compile("gen.Broken", source);
        assertThat(r.success()).isFalse();
        assertThat(r.diagnostics()).isNotEmpty();
    }

    @Test
    void synthesizedSinglePathClassCompiles() {
        Endpoint ep = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());
        String source = TestSynthesizer.synthesize(
                new io.graphrag.generator.core.SynthesisInput(ep, List.of(), "gen"));

        CompileResult r = JavaSourceCompiler.compile("gen.OrdersPostTest", source);

        assertThat(r.success())
                .as("synthesized code should compile cleanly. diagnostics=" + r.diagnostics())
                .isTrue();
    }

    @Test
    void synthesizedMultiPathClassCompiles() {
        Endpoint ep = new Endpoint(
                "POST:/api/orders", HttpMethod.POST, "/api/orders",
                "demo-sut", "OrdersController", "create", false, List.of());

        ExploredPath p1 = new ExploredPath("p1", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(),
                        Map.of("userId", "u-1", "amount", 100, "type", "EXPRESS")),
                null, List.of(), 201, null, "cov-p1", "v1");
        ExploredPath p2 = new ExploredPath("p2", ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of("amount", 0)),
                null, List.of(), 400, null, "cov-p2", "v1");

        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                ep,
                List.of(new PathContext(p1, List.of()), new PathContext(p2, List.of())),
                "gen");

        String source = TestSynthesizer.synthesizeMulti(input);

        CompileResult r = JavaSourceCompiler.compile("gen.OrdersPostTest", source);
        assertThat(r.success())
                .as("multi-path synthesis should produce compilable Java. diagnostics="
                        + r.diagnostics())
                .isTrue();
    }
}
