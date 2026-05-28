package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * One control-flow branch surfaced from a method body.
 *
 * @param id                  stable identifier: {@code "{classFqn}#{method}:line{N}"}
 * @param kind                AST node category (see {@link BranchKind})
 * @param condition           raw source text of the condition (may be empty for THROW/RETURN)
 * @param lineNumber          1-based source line of the AST node
 * @param referencedVariables identifiers appearing in {@code condition}, deduplicated and sorted
 */
public record Branch(
        String id,
        BranchKind kind,
        String condition,
        int lineNumber,
        List<String> referencedVariables) {

    public Branch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(condition, "condition");
        referencedVariables = List.copyOf(Objects.requireNonNull(referencedVariables, "referencedVariables"));
    }
}
