package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class RouterFunctionFixtureIT {
    @Test
    void functionalRoutes_mergeIntoEndpointIndex(@TempDir Path dir) throws Exception {  // REQ-001/004
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r() {\n"
              + "    return route()\n"
              + "      .POST(\"/internal/counseling/sessions\", req -> ServerResponse.ok().build())\n"
              + "      .GET(\"/internal/counseling/sessions/{id}\", req -> ServerResponse.ok().build())\n"
              + "      .build();\n"
              + "  }\n}\n");

        IndexResult annotated = new EndpointIndexer().index(dir);     // @RestController 0 → empty
        IndexResult functional = new RouterFunctionIndexer().index(dir);
        IndexResult merged = annotated.merge(functional);

        assertThat(merged.endpoints()).extracting(Endpoint::path)
                .contains("/internal/counseling/sessions", "/internal/counseling/sessions/{id}");
        assertThat(merged.endpoints()).hasSizeGreaterThanOrEqualTo(2);   // E2E-1 threshold: >=2
    }
}
