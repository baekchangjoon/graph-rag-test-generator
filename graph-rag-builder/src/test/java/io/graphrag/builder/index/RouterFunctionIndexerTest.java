package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RouterFunctionIndexerTest {

    private static Path writeRouter(Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.RouterFunction;\n"
              + "import org.springframework.web.reactive.function.server.ServerResponse;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route()\n"
              + "      .POST(\"/internal/counseling/sessions\", h::create)\n"
              + "      .POST(\"/internal/counseling/sessions/{id}/messages\", h::addMessage)\n"
              + "      .build();\n"
              + "  }\n"
              + "}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.ServerRequest;\n"
              + "import org.springframework.web.reactive.function.server.ServerResponse;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> create(ServerRequest req) { return ServerResponse.ok().build(); }\n"
              + "  Mono<ServerResponse> addMessage(ServerRequest req) { return ServerResponse.ok().build(); }\n"
              + "}\n");
        return dir;
    }

    @Test
    void index_discoversFunctionalRoutes(@TempDir Path dir) throws Exception {  // REQ-001
        IndexResult result = new RouterFunctionIndexer().index(writeRouter(dir));

        assertThat(result.endpoints())
                .extracting(Endpoint::httpMethod, Endpoint::path)
                .containsExactlyInAnyOrder(
                        tuple("POST", "/internal/counseling/sessions"),
                        tuple("POST", "/internal/counseling/sessions/{id}/messages"));
        assertThat(result.endpoints()).allSatisfy(e ->
                assertThat(e.handlerClass()).isEqualTo("com.x.Routes"));
    }
}
