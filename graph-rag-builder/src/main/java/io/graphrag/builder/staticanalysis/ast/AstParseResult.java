package io.graphrag.builder.staticanalysis.ast;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of running {@link AstParser#parse(java.nio.file.Path)} on a source
 * directory. Both lists are returned in deterministic order (path sort order),
 * so two runs on the same input produce equal results.
 */
public record AstParseResult(List<ParsedFile> parsedFiles, List<ParseFailure> failures) {

    public AstParseResult {
        parsedFiles = List.copyOf(Objects.requireNonNull(parsedFiles, "parsedFiles"));
        failures    = List.copyOf(Objects.requireNonNull(failures,    "failures"));
    }
}
