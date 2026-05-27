package io.graphrag.discovery;

import io.graphrag.model.HttpMethod;

import java.util.List;
import java.util.Objects;

/**
 * Intermediate fact produced by {@link ControllerScanner}: a single REST handler
 * method's signature in static-analysis terms.
 *
 * @param method        the HTTP method as inferred from the mapping annotation
 * @param path          full path (class-level base path joined with method-level path)
 * @param handlerClass  fully-qualified class name
 * @param handlerMethod method simple name
 * @param pathParams    declared {@code @PathVariable} parameters in declaration order;
 *                      used by {@link io.graphrag.discovery.heuristic.BoundaryValueGenerator}
 *                      to decide which to mutate
 */
public record DiscoveredHandler(
        HttpMethod method,
        String path,
        String handlerClass,
        String handlerMethod,
        List<HandlerParam> pathParams,
        List<HandlerParam> queryParams,
        boolean hasRequestBody) {

    public DiscoveredHandler {
        Objects.requireNonNull(method);
        Objects.requireNonNull(path);
        Objects.requireNonNull(handlerClass);
        Objects.requireNonNull(handlerMethod);
        pathParams = List.copyOf(Objects.requireNonNullElse(pathParams, List.of()));
        queryParams = List.copyOf(Objects.requireNonNullElse(queryParams, List.of()));
    }
}
