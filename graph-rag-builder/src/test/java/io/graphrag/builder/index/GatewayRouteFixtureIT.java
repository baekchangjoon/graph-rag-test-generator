package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteFixtureIT {
    @Test
    void gatewayRoutes_mergeIntoEndpointIndex(@TempDir Path dir) throws Exception {  // REQ-005
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("GatewayConfig.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.cloud.gateway.route.RouteLocator;\n"
              + "import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;\n"
              + "public class GatewayConfig {\n"
              + "  @Bean RouteLocator routes(RouteLocatorBuilder b) {\n"
              + "    return b.routes()\n"
              + "      .route(\"orders\", r -> r.path(\"/api/v1/orders/**\").uri(\"http://orders\"))\n"
              + "      .build();\n"
              + "  }\n}\n");

        IndexResult annotated = new EndpointIndexer().index(dir);   // no @RestController → empty
        IndexResult gateway = new GatewayRouteIndexer().index(dir);
        IndexResult merged = annotated.merge(gateway);

        assertThat(merged.endpoints()).extracting(Endpoint::path, Endpoint::targetUri)
                .contains(org.assertj.core.api.Assertions.tuple("/api/v1/orders/**", "http://orders"));
        assertThat(merged.endpoints()).hasSizeGreaterThanOrEqualTo(1);   // REQ-005 threshold: >=1
    }
}
