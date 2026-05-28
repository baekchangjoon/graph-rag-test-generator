package io.graphrag.builder.staticanalysis.domain;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Extracts control-flow branches from a single method body.
 *
 * <p>For each handled AST node, emits a {@link Branch} with:
 * <ul>
 *   <li>{@code id}     — {@code "{classFqn}#{method}:line{N}"}</li>
 *   <li>{@code kind}   — see {@link BranchKind}</li>
 *   <li>{@code condition} — raw source text of the condition (or selector for SWITCH;
 *       empty string for THROW since there is no condition expression on the node itself)</li>
 *   <li>{@code lineNumber}          — 1-based source line</li>
 *   <li>{@code referencedVariables} — deduplicated, alphabetically sorted identifiers
 *       from the condition</li>
 * </ul>
 *
 * <p>Result is ordered by {@code lineNumber} for determinism.
 */
public final class BranchExtractor {

    private BranchExtractor() {}

    public static List<Branch> extract(MethodDeclaration method, String classFqn) {
        List<Branch> out = new ArrayList<>();
        String methodName = method.getNameAsString();

        method.findAll(IfStmt.class).forEach(stmt -> {
            String cond = stmt.getCondition().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.IF,
                    cond,
                    lineOf(stmt),
                    referencedVariables(stmt.getCondition())));
        });

        method.findAll(SwitchStmt.class).forEach(stmt -> {
            String selector = stmt.getSelector().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.SWITCH,
                    selector,
                    lineOf(stmt),
                    referencedVariables(stmt.getSelector())));
        });

        method.findAll(ConditionalExpr.class).forEach(expr -> {
            String cond = expr.getCondition().toString();
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(expr)),
                    BranchKind.TERNARY,
                    cond,
                    lineOf(expr),
                    referencedVariables(expr.getCondition())));
        });

        method.findAll(ThrowStmt.class).forEach(stmt -> {
            out.add(new Branch(
                    id(classFqn, methodName, lineOf(stmt)),
                    BranchKind.THROW,
                    "",
                    lineOf(stmt),
                    List.of()));
        });

        out.sort(Comparator.comparingInt(Branch::lineNumber));
        return out;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static String id(String classFqn, String methodName, int line) {
        return classFqn + "#" + methodName + ":line" + line;
    }

    private static List<String> referencedVariables(com.github.javaparser.ast.Node node) {
        // TreeSet gives dedup + alphabetical ordering for free.
        TreeSet<String> names = new TreeSet<>();
        node.findAll(NameExpr.class).forEach(ne -> names.add(ne.getNameAsString()));
        return List.copyOf(names);
    }
}
