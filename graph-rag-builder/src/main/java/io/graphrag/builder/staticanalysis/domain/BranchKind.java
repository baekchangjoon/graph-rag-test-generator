package io.graphrag.builder.staticanalysis.domain;

/** AST node category that {@link BranchExtractor} surfaces as a {@link Branch}. */
public enum BranchKind {
    IF,
    SWITCH,
    TERNARY,
    THROW,
    RETURN
}
