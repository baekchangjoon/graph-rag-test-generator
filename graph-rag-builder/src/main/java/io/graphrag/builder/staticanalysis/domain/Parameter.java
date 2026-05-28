package io.graphrag.builder.staticanalysis.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A method parameter as seen at the AST level. Annotation simple names are
 * preserved verbatim (e.g. {@code "PathVariable"}, {@code "RequestBody"}).
 *
 * <p>{@code annotationValues} carries the primary value of each annotation, when
 * present. For {@code @PathVariable("ownerId")} or {@code @PathVariable(name = "ownerId")},
 * the entry is {@code "PathVariable" -> "ownerId"}. Marker annotations
 * ({@code @PathVariable} with no arguments) contribute no entry.
 */
public record Parameter(
        String name,
        String type,
        List<String> annotations,
        Map<String, String> annotationValues) {

    public Parameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        annotationValues = Map.copyOf(Objects.requireNonNull(annotationValues, "annotationValues"));
    }

    /**
     * Backward-compatible constructor for sites that don't carry annotation values
     * (mostly tests + legacy code paths). Equivalent to the 4-arg form with an
     * empty annotation-values map.
     */
    public Parameter(String name, String type, List<String> annotations) {
        this(name, type, annotations, Map.of());
    }
}
