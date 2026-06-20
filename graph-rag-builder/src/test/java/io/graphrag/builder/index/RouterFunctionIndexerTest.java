package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
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
    void index_returnsEmpty_whenNoRouterPresent(@TempDir Path dir) throws Exception {  // REQ-001 negative
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Plain.java"),
                "package com.x;\n"
              + "public class Plain {\n"
              + "  public void foo() {}\n"
              + "}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);

        assertThat(result.endpoints()).isEmpty();
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

    @Test
    void index_extractsBodyShapeFromBodyToFlux(@TempDir Path dir) throws Exception {  // REQ-002 bodyToFlux
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package com.x;\npublic record Dto(String title, int score) {}\n");
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().POST(\"/items\", h::create).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Flux;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> create(ServerRequest req) {\n"
              + "    return req.bodyToFlux(Dto.class).collectList().flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);
        io.graphrag.model.Endpoint ep = result.endpoints().get(0);
        assertThat(ep.path()).isEqualTo("/items");
        assertThat(ep.params()).extracting(io.graphrag.model.EndpointParam::name, io.graphrag.model.EndpointParam::kind)
                .contains(tuple("body", io.graphrag.model.ParamKind.BODY));
        assertThat(result.bodyShapes().get("com.x.Dto")).isNotNull();
        assertThat(result.bodyShapes().get("com.x.Dto").fields())
                .extracting(BodyShape.BodyField::name).contains("title", "score");
    }

    @Test
    void index_extractsBodyShapeAndPathVar(@TempDir Path dir) throws Exception {  // REQ-002
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package com.x;\npublic record Dto(String title, int score) {}\n");
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().POST(\"/sessions/{id}/messages\", h::add).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> add(ServerRequest req) {\n"
              + "    String id = req.pathVariable(\"id\");\n"
              + "    return req.bodyToMono(Dto.class).flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);
        io.graphrag.model.Endpoint ep = result.endpoints().get(0);
        assertThat(ep.params()).extracting(io.graphrag.model.EndpointParam::name, io.graphrag.model.EndpointParam::kind)
                .contains(tuple("id", io.graphrag.model.ParamKind.PATH),
                          tuple("body", io.graphrag.model.ParamKind.BODY));
        assertThat(result.bodyShapes().get("com.x.Dto").fields())
                .extracting(BodyShape.BodyField::name).contains("title", "score");
    }
}
