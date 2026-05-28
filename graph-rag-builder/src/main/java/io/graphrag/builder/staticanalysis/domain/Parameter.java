package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Objects;

/**
 * A method parameter as seen at the AST level. Annotation simple names are
 * preserved verbatim (e.g. {@code "PathVariable"}, {@code "RequestBody"}).
 */
public record Parameter(String name, String type, List<String> annotations) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
    }
}
