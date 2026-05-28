package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.graphrag.builder.staticanalysis.ast.SymbolResolverFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphBuilderTest {

    @Test
    void resolved_in_project_call_recorded(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/C.java");
        Files.writeString(src, """
            package demo;
            class C {
                int add(int a, int b) { return a + b; }
                int caller() { return add(1, 2); }
            }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        MethodDeclaration caller = methodNamed(b.parser().parse(src).getResult().orElseThrow(), "caller");

        List<MethodCall> calls = b.outgoingCalls(caller);

        assertThat(calls).hasSize(1);
        MethodCall mc = calls.get(0);
        assertThat(mc.calleeMethodName()).isEqualTo("add");
        assertThat(mc.resolved()).isTrue();
        assertThat(mc.calleeClassFqn()).isEqualTo("demo.C");
    }

    @Test
    void unresolved_call_recorded_as_unresolved(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/D.java");
        Files.writeString(src, """
            package demo;
            class D { void caller() { com.example.Unknown.invoke(); } }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        MethodDeclaration caller = methodNamed(b.parser().parse(src).getResult().orElseThrow(), "caller");

        List<MethodCall> calls = b.outgoingCalls(caller);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).resolved()).isFalse();
        assertThat(calls.get(0).calleeMethodName()).isEqualTo("invoke");
        assertThat(calls.get(0).calleeClassFqn()).isNull();
    }

    @Test
    void external_resolved_call_excluded_from_edges(@TempDir Path tmp) throws Exception {
        // String#length is resolvable via ReflectionTypeSolver but is not "in project".
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/E.java");
        Files.writeString(src, """
            package demo;
            class E { int caller(String s) { return s.length(); } }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        CompilationUnit cu = b.parser().parse(src).getResult().orElseThrow();
        Set<String> inProjectClassFqns = Set.of("demo.E");

        CallGraph g = b.build(List.of(cu), inProjectClassFqns);

        // demo.E#caller exists as a key but has no in-project edges.
        assertThat(g.edges()).containsKey("demo.E#caller");
        assertThat(g.edges().get("demo.E#caller")).isEmpty();
    }

    @Test
    void cycle_records_both_directions(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Path src = tmp.resolve("demo/Cycle.java");
        Files.writeString(src, """
            package demo;
            class Cycle {
                void a() { b(); }
                void b() { a(); }
            }
            """);

        CallGraphBuilder b = new CallGraphBuilder(tmp);
        CompilationUnit cu = b.parser().parse(src).getResult().orElseThrow();
        CallGraph g = b.build(List.of(cu), Set.of("demo.Cycle"));

        assertThat(g.edges().get("demo.Cycle#a")).containsExactly("demo.Cycle#b");
        assertThat(g.edges().get("demo.Cycle#b")).containsExactly("demo.Cycle#a");
    }

    private static MethodDeclaration methodNamed(CompilationUnit cu, String name) {
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst().orElseThrow();
    }
}
