package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import io.graphrag.builder.staticanalysis.ast.SymbolResolverFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds {@link CallGraph} edges by walking {@link MethodCallExpr} nodes in each
 * supplied method and attempting symbol resolution against the source root +
 * any classpath jars provided to the underlying {@link JavaParser}.
 *
 * <p>An unresolved call produces a {@link MethodCall} with
 * {@code resolved=false, calleeClassFqn=null}, useful for downstream
 * manual-review reporting in Stage 3.
 *
 * <p>{@link #build(List, Set)} aggregates edges only when the callee class is
 * in the supplied {@code inProjectClassFqns} set — external library calls are
 * excluded from the graph even when their symbol resolves successfully.
 */
public final class CallGraphBuilder {

    private final JavaParser parser;

    public CallGraphBuilder(Path sourceRoot) {
        this(sourceRoot, List.of());
    }

    public CallGraphBuilder(Path sourceRoot, List<Path> classpathJars) {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        this.parser = new JavaParser(new ParserConfiguration()
                .setSymbolResolver(SymbolResolverFactory.create(sourceRoot, classpathJars)));
    }

    public JavaParser parser() { return parser; }

    /** Outgoing calls from a single method body, ordered by source line for determinism. */
    public List<MethodCall> outgoingCalls(MethodDeclaration method) {
        List<MethodCall> out = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            int line = call.getRange().map(r -> r.begin.line).orElse(0);
            try {
                ResolvedMethodDeclaration r = call.resolve();
                out.add(new MethodCall(
                        r.declaringType().getQualifiedName(),
                        r.getName(),
                        line,
                        true));
            } catch (Throwable t) {
                out.add(new MethodCall(
                        null,
                        call.getNameAsString(),
                        line,
                        false));
            }
        }
        out.sort(Comparator.comparingInt(MethodCall::line));
        return out;
    }

    /**
     * Aggregate edges across multiple parsed compilation units. Only callees
     * whose resolved declaring class FQN appears in {@code inProjectClassFqns}
     * are recorded; all method keys (including those with no in-project edges)
     * still appear in {@link CallGraph#edges()} so callers can iterate every method.
     */
    public CallGraph build(List<CompilationUnit> units, Set<String> inProjectClassFqns) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                String classFqn = method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .map(t -> pkg.isEmpty() ? t.getNameAsString() : pkg + "." + t.getNameAsString())
                        .orElse(pkg);
                String key = classFqn + "#" + method.getNameAsString();
                List<String> callees = new ArrayList<>();
                for (MethodCall mc : outgoingCalls(method)) {
                    if (mc.resolved() && inProjectClassFqns.contains(mc.calleeClassFqn())) {
                        callees.add(mc.calleeClassFqn() + "#" + mc.calleeMethodName());
                    }
                }
                edges.put(key, List.copyOf(callees));
            });
        }
        return new CallGraph(edges);
    }
}
