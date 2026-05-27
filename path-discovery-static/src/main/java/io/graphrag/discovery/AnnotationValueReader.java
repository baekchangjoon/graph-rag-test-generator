package io.graphrag.discovery;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Small helpers for reading values out of {@link AnnotationExpr} nodes without depending
 * on a full symbol resolver. We only need string-literal extraction (for {@code path = "..."}
 * style attributes) and {@link FieldAccessExpr} reading (for
 * {@code method = RequestMethod.POST}).
 *
 * <p>Anything cleverer (e.g. constants pulled from another file) is intentionally not
 * supported — Spring path strings in real code are overwhelmingly literal, and resolving
 * arbitrary expressions would drag in a symbol-solver. Failure to interpret here returns
 * empty, and the caller decides what to do.
 */
final class AnnotationValueReader {

    private AnnotationValueReader() {}

    /**
     * Reads the "primary" string value of an annotation. Handles:
     * <pre>
     *   @GetMapping              → []
     *   @GetMapping("/x")        → ["/x"]
     *   @GetMapping({"/x", "/y"}) → ["/x", "/y"]
     *   @GetMapping(value = "/x") → ["/x"]
     *   @GetMapping(path  = "/x") → ["/x"]
     * </pre>
     * Returns the first string only when the annotation has a single-member form.
     * For an array, returns all literals. Returns empty when the annotation has no value.
     */
    static List<String> readPaths(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s) {
            return literalsFrom(s.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr n) {
            return n.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString())
                              || "path".equals(p.getNameAsString()))
                    .findFirst()
                    .map(p -> literalsFrom(p.getValue()))
                    .orElse(List.of());
        }
        return List.of();
    }

    /**
     * Reads a {@code method = RequestMethod.POST} attribute. Returns the right-hand
     * simple name (e.g. "POST") or empty.
     */
    static Optional<String> readMethodAttribute(NormalAnnotationExpr ann) {
        return ann.getPairs().stream()
                .filter(p -> "method".equals(p.getNameAsString()))
                .findFirst()
                .map(p -> {
                    Expression v = p.getValue();
                    if (v instanceof FieldAccessExpr fa) return fa.getNameAsString();
                    if (v instanceof ArrayInitializerExpr arr && arr.getValues().size() == 1
                            && arr.getValues().get(0) instanceof FieldAccessExpr fa) {
                        return fa.getNameAsString();
                    }
                    return null;
                });
    }

    private static List<String> literalsFrom(Expression e) {
        if (e instanceof StringLiteralExpr lit) return List.of(lit.getValue());
        if (e instanceof ArrayInitializerExpr arr) {
            return arr.getValues().stream()
                    .filter(v -> v instanceof StringLiteralExpr)
                    .map(v -> ((StringLiteralExpr) v).getValue())
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
