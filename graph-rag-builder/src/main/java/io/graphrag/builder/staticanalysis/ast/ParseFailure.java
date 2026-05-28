package io.graphrag.builder.staticanalysis.ast;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One {@code .java} file that {@link AstParser} could not parse. The rest of
 * the parse keeps going — a single broken file never aborts the whole scan.
 */
public record ParseFailure(Path sourcePath, String message) {

    public ParseFailure {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(message, "message");
    }
}
