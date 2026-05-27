package io.graphrag.discovery;

/**
 * One Spring handler-method parameter. We only care about: name, declared type, and
 * which annotation introduced it ({@code @PathVariable} / {@code @RequestParam} /
 * {@code @RequestBody}).
 *
 * <p>{@code typeName} is the source-level simple name as it appears in the AST — e.g.
 * {@code "Integer"}, {@code "int"}, {@code "Long"}, {@code "String"}. Boundary-value
 * generation in {@link io.graphrag.discovery.heuristic.BoundaryValueGenerator} only
 * needs to recognize numeric vs non-numeric types so we don't bother with full type
 * resolution.
 */
public record HandlerParam(String name, String typeName, ParamSource source) {

    public enum ParamSource { PATH, QUERY, BODY }
}
