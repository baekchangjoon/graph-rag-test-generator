package io.graphrag.builder.staticanalysis.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolResolverFactoryTest {

    @Test
    void resolves_in_source_method_call(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Demo.java");
        Files.writeString(src, """
            package demo;
            class Demo {
                int add(int a, int b) { return a + b; }
                int call() { return add(1, 2); }
            }
            """);

        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));

        CompilationUnit cu = parser.parse(src).getResult().orElseThrow();
        MethodCallExpr call = cu.findFirst(MethodCallExpr.class).orElseThrow();

        assertThat(call.resolve().getName()).isEqualTo("add");
        assertThat(call.resolve().declaringType().getQualifiedName()).isEqualTo("demo.Demo");
    }

    @Test
    void unresolvable_call_does_not_throw_at_solver_creation(@TempDir Path tmp) {
        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        assertThat(solver).isNotNull();
    }

    @Test
    void resolving_unknown_call_throws_UnsolvedSymbolException(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("Demo.java");
        Files.writeString(src, """
            package demo;
            class Demo { void call() { com.example.External.unknown(); } }
            """);

        JavaSymbolSolver solver = SymbolResolverFactory.create(tmp, List.of());
        JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));

        CompilationUnit cu = parser.parse(src).getResult().orElseThrow();
        MethodCallExpr call = cu.findFirst(MethodCallExpr.class).orElseThrow();

        assertThatThrownBy(call::resolve).isInstanceOf(UnsolvedSymbolException.class);
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable>
            assertThatThrownBy(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(c);
    }
}
