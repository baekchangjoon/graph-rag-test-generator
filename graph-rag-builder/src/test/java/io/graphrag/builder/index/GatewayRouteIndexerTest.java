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
