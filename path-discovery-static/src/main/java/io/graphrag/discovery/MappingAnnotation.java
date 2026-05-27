package io.graphrag.discovery;

import io.graphrag.model.HttpMethod;

import java.util.Map;

/**
 * Recognized Spring HTTP-mapping annotations and their implied {@link HttpMethod}.
 *
 * <p>Class-level {@code @RequestMapping} (no method specified) is intentionally not
 * a leaf — it only contributes a base path. Method-level {@code @RequestMapping} with
 * explicit {@code method = RequestMethod.X} is handled separately by the scanner.
 */
final class MappingAnnotation {

    private MappingAnnotation() {}

    /** Method-level annotation simple-name → implied HTTP method. */
    static final Map<String, HttpMethod> METHOD_LEVEL = Map.of(
            "GetMapping",    HttpMethod.GET,
            "PostMapping",   HttpMethod.POST,
            "PutMapping",    HttpMethod.PUT,
            "DeleteMapping", HttpMethod.DELETE,
            "PatchMapping",  HttpMethod.PATCH);

    /** Class-level "I'm a controller" annotations. */
    static boolean isControllerAnnotation(String simpleName) {
        return "RestController".equals(simpleName) || "Controller".equals(simpleName);
    }

    /** True for any of {@code @GetMapping}…{@code @PatchMapping}. */
    static boolean isShorthandMethodMapping(String simpleName) {
        return METHOD_LEVEL.containsKey(simpleName);
    }

    /** True for {@code @RequestMapping} (class or method level). */
    static boolean isRequestMapping(String simpleName) {
        return "RequestMapping".equals(simpleName);
    }
}
