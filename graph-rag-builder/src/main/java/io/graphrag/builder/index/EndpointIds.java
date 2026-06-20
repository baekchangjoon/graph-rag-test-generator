package io.graphrag.builder.index;

/** Single source for the endpoint id naming scheme shared across all indexers. */
final class EndpointIds {

    private EndpointIds() {}

    /**
     * Returns a stable, URL-safe endpoint id from an HTTP method and path.
     *
     * <p>The scheme: concatenate {@code method} and {@code path}, lower-case the result,
     * replace every run of non-alphanumeric characters with {@code -}, then strip any
     * leading or trailing {@code -}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code of("POST", "/api/orders")} → {@code "post-api-orders"}</li>
     *   <li>{@code of("GET", "/api/orders/{id}")} → {@code "get-api-orders-id"}</li>
     * </ul>
     */
    static String of(String httpMethod, String path) {
        return (httpMethod + path).toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
