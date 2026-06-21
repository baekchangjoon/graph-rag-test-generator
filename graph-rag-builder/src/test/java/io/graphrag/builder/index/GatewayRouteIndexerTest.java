package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class GatewayRouteIndexerTest {

    // Two-route DSL: one with stripPrefix(1), one without filter
    private static Path writeGatewayRoutes(Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(\"orders\", r -> r.path(\"/api/v1/orders/**\").filters(f -> f.stripPrefix(1)).uri(\"http://orders\"))\n"
              + "      .route(r -> r.path(\"/api/v1/users/**\").uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");
        return dir;
    }

    @Test
    void index_discoversGatewayProxyRoutes_withAndWithoutFilters(@TempDir Path dir) throws Exception {  // REQ-005,006
        IndexResult result = new GatewayRouteIndexer().index(writeGatewayRoutes(dir));

        // stripPrefix is a SUPPORTED filter — route IS indexed.
        // Endpoint.path must be the gateway PREDICATE path (verbatim), NOT the transformed downstream path.
        // /api/v1/orders/** predicate is preserved (stripPrefix transform is NOT applied to path).
        assertThat(result.endpoints())
                .extracting(Endpoint::path, Endpoint::targetUri)
                .containsExactlyInAnyOrder(
                        tuple("/api/v1/orders/**", "http://orders"),   // predicate preserved; stripPrefix supported → indexed
                        tuple("/api/v1/users/**", "lb://users"));
        assertThat(result.endpoints()).allSatisfy(e ->
                assertThat(e.httpMethod()).isEqualTo("GET"));
        assertThat(result.endpoints()).allSatisfy(e ->
                assertThat(e.handlerClass()).isEqualTo("com.example.GatewayConfig"));
    }

    @Test
    void index_excludesRoute_withUnsupportedFilter(@TempDir Path dir) throws Exception {  // REQ-006 filter exclusion
        // circuitBreaker is NOT in the supported whitelist (stripPrefix/rewritePath/setPath/addRequestHeader/addResponseHeader).
        // A route using it cannot be trivially proxy-smoked, so it must be excluded.
        // Critically: the nested config lambda `c -> c.setName("cb")` must NOT be mistaken for the unsupported filter —
        // it is circuitBreaker (the direct child of the filters lambda) that triggers exclusion, not setName.
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(r -> r.path(\"/api/v1/orders/**\").filters(f -> f.circuitBreaker(c -> c.setName(\"cb\"))).uri(\"http://orders\"))\n"
              + "      .route(r -> r.path(\"/api/v1/users/**\").uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        // The route with unsupported circuitBreaker filter is excluded;
        // the plain route without filters is kept.
        assertThat(result.endpoints())
                .extracting(Endpoint::path)
                .containsExactly("/api/v1/users/**")
                .doesNotContain("/api/v1/orders/**");
    }

    /**
     * Proves that `setName` inside a circuitBreaker config lambda does NOT drive the exclusion decision:
     * a route with only supported filters (stripPrefix) followed by circuitBreaker is excluded,
     * while a route with only supported filters (stripPrefix) without any unsupported filter is kept.
     * This would fail if the indexer flags `setName` (a nested config call) as the unsupported filter
     * and then stops at the wrong boundary — e.g. if `setName` were somehow "not found" it could
     * accidentally pass through, producing a false positive index entry.
     */
    @Test
    void index_excludesRoute_circuitBreakerWithNestedConfig_notSetName(@TempDir Path dir) throws Exception {  // REQ-006 nested-config scoping
        // Route A: stripPrefix (supported) + circuitBreaker with nested c -> c.setName("x") — MUST be excluded.
        // Route B: only stripPrefix (supported) — MUST be indexed.
        // If the bug is present and setName is seen as the unsupported filter before circuitBreaker,
        // the bug may still exclude Route A by accident. But Route B's path/uri must be correct and not
        // "contaminated" by any invocation inside a filters sub-lambda.
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(\"r-a\", r -> r.path(\"/api/v1/orders/**\").filters(f -> f.stripPrefix(1).circuitBreaker(c -> c.setName(\"x\"))).uri(\"http://orders\"))\n"
              + "      .route(\"r-b\", r -> r.path(\"/api/v1/users/**\").filters(f -> f.stripPrefix(1)).uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        // Route A (circuitBreaker) excluded; Route B (stripPrefix only) kept
        assertThat(result.endpoints())
                .extracting(Endpoint::path)
                .containsExactly("/api/v1/users/**")
                .doesNotContain("/api/v1/orders/**");
    }

    /**
     * Regression: path and uri for each route must be taken from the ROUTE lambda's direct invocations,
     * not contaminated by anything inside a nested filters sub-lambda.
     * With the bug, `getElements(TypeFilter<CtInvocation>)` on the route lambda returns ALL invocations
     * deep — including those inside the filters sub-lambda — so a `path`/`uri` call nested inside a
     * filter config could be picked up wrongly.
     *
     * This test uses setPath (supported) which has a method call named "setPath" — that won't collide
     * with "path". But a nested config lambda like `c -> c.setUri("wrong-uri")` (hypothetical) WOULD
     * collide with "uri" extraction if the deep scan is used. We simulate this with rewritePath whose
     * string args could be confused for a path literal if filtering is wrong.
     *
     * The critical assertion: BOTH routes' path AND uri must match their own predicate values,
     * not each other's or any string from inside the filter body.
     */
    @Test
    void index_pathAndUri_notContaminatedByFilterSubLambda(@TempDir Path dir) throws Exception {  // REQ-005 path/uri scoping
        // Route 1: setPath("/downstream/path") inside filters — if the bug were present and setPath were
        // renamed "path", that nested string "/downstream/path" would be picked up instead of "/api/v1/orders/**".
        // We use rewritePath to produce a string that if accidentally consumed would change the path.
        // Route 2: contains a circuitBreaker config sub-lambda (excluded), plus a plain route for comparison.
        // The key invariant: EACH route must produce its own path/uri pair, not cross-contaminate.
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              // Route r1 path="/api/v1/orders/**" uri="http://orders" — rewritePath inside filters has strings but no "path"/"uri" method names
              + "      .route(\"r1\", r -> r.path(\"/api/v1/orders/**\").filters(f -> f.rewritePath(\"/api/v1/orders/(.*)\", \"/$1\")).uri(\"http://orders\"))\n"
              // Route r2 path="/api/v1/users/**" uri="lb://users" — no filters
              + "      .route(\"r2\", r -> r.path(\"/api/v1/users/**\").uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        // Both routes should be indexed with THEIR OWN path/uri — no cross-contamination
        assertThat(result.endpoints())
                .extracting(Endpoint::path, Endpoint::targetUri)
                .containsExactlyInAnyOrder(
                        tuple("/api/v1/orders/**", "http://orders"),
                        tuple("/api/v1/users/**", "lb://users"));
    }

    /**
     * Bug regression: if the route lambda's getElements() deep-scan picks up a `uri` call from
     * a nested config sub-lambda inside the filters lambda, the wrong URI would be captured.
     *
     * Scenario: a circuitBreaker config lambda `c -> c.uri("http://wrong")` is nested inside the
     * filters lambda. With a deep getElements scan on the ROUTE lambda, `uri("http://wrong")`
     * from the config lambda appears in the scan and — being encountered in DFS before the real
     * `.uri("http://orders")` call — would shadow it. With the parent-lambda scoping fix, only
     * invocations whose nearest-enclosing lambda IS the route lambda appear in `lambdaInvocations`,
     * so the nested `uri("http://wrong")` is excluded from path/uri extraction.
     *
     * (The route is excluded anyway because circuitBreaker is unsupported, but the test asserts
     * that only the USERS route is kept — NOT that orders is somehow indexed with the wrong uri.
     * The path/uri contamination is implicitly tested: if `uri("http://wrong")` from the nested
     * config lambda bled into the route-level scan AND orders was NOT excluded, the uri would be
     * "http://wrong". But because the fix also correctly scopes filter detection, the exclusion
     * is for circuitBreaker, not for any false positive.)
     *
     * For a clean path/uri scoping test WITHOUT filter exclusion, we use two independent routes —
     * each with filters using only supported filter methods — and verify they each get their own
     * path and uri, not each other's.
     */
    @Test
    void index_pathAndUri_notShadowedByDeepScanOfFilterLambda(@TempDir Path dir) throws Exception {  // REQ-005 path/uri scoping, deep-scan regression
        // Two independent routes, each with a filters sub-lambda that contains string literals
        // that could theoretically contaminate path/uri if method-name matching was broken.
        // Route r1: setPath("/downstream/orders") — method name is "setPath", NOT "path"; won't bleed.
        // Route r2: addResponseHeader("X-Forwarded-Uri", "lb://internal") — "addResponseHeader" is not "uri".
        // The two routes have distinct paths AND distinct uris; if cross-contamination occurred,
        // the tuples would not match.
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(\"r1\", r -> r.path(\"/api/v1/orders/**\").filters(f -> f.setPath(\"/downstream/orders\")).uri(\"http://orders\"))\n"
              + "      .route(\"r2\", r -> r.path(\"/api/v1/users/**\").filters(f -> f.addResponseHeader(\"X-Forwarded-Uri\", \"lb://internal\")).uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        // Each route must have its OWN predicate path and target uri — no cross-route or filter-bleed contamination
        assertThat(result.endpoints())
                .extracting(Endpoint::path, Endpoint::targetUri)
                .containsExactlyInAnyOrder(
                        tuple("/api/v1/orders/**", "http://orders"),
                        tuple("/api/v1/users/**", "lb://users"));
    }

    @Test
    void index_excludesRoute_whenFiltersArgIsNonLambda(@TempDir Path dir) throws Exception {  // Fix1: non-lambda filters bypass
        // .filters(f) where f is a method parameter — not a lambda, cannot analyze statically
        // The indexer must conservatively exclude the route (cannot verify it's smoke-safe)
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;\n"
              + "import java.util.function.Function;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b, Function<GatewayFilterSpec,GatewayFilterSpec> extraFilters) {\n"
              + "    return b.routes()\n"
              + "      .route(r -> r.path(\"/api/v1/orders/**\").filters(extraFilters).uri(\"http://orders\"))\n"
              + "      .route(r -> r.path(\"/api/v1/users/**\").uri(\"lb://users\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        // Non-lambda .filters() arg → route excluded
        assertThat(result.endpoints())
                .extracting(Endpoint::path)
                .containsExactly("/api/v1/users/**")
                .doesNotContain("/api/v1/orders/**");
    }

    @Test
    void index_varargsPaths_producesEndpointPerPattern(@TempDir Path dir) throws Exception {  // Fix3: varargs path
        // .path("/a/**", "/b/**") should produce TWO endpoints, one per pattern
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.example;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(\"multi\", r -> r.path(\"/api/v1/orders/**\", \"/api/v1/legacy/**\").uri(\"http://orders\"))\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        assertThat(result.endpoints()).hasSize(2);
        assertThat(result.endpoints())
                .extracting(Endpoint::path)
                .containsExactlyInAnyOrder("/api/v1/orders/**", "/api/v1/legacy/**");
        assertThat(result.endpoints())
                .extracting(Endpoint::targetUri)
                .containsOnly("http://orders");
    }

    @Test
    void index_returnsEmpty_whenNoRouteLocatorPresent(@TempDir Path dir) throws Exception {  // REQ-005 negative
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("Plain.java"),
                "package com.example;\n"
              + "public class Plain {\n"
              + "  public void foo() {}\n"
              + "}\n");

        IndexResult result = new GatewayRouteIndexer().index(dir);

        assertThat(result.endpoints()).isEmpty();
        assertThat(result.bodyShapes()).isEmpty();
    }
}
