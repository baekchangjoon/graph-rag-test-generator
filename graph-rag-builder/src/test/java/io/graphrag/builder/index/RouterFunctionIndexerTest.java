package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void index_extractsBodyShapeFromBodyToFlux(@TempDir Path dir) throws Exception {  // REQ-002 bodyToFlux — Fix A
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
              + "    return route()\n"
              + "      .POST(\"/items\", h::createFlux)\n"
              + "      .POST(\"/item\", h::createMono)\n"
              + "      .build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Flux;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> createFlux(ServerRequest req) {\n"
              + "    return req.bodyToFlux(Dto.class).collectList().flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n"
              + "  Mono<ServerResponse> createMono(ServerRequest req) {\n"
              + "    return req.bodyToMono(Dto.class).flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n"
              + "}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);

        // bodyToFlux route (/items): shape must have collection=true
        io.graphrag.model.Endpoint fluxEp = result.endpoints().stream()
                .filter(e -> e.path().equals("/items")).findFirst().orElseThrow();
        assertThat(fluxEp.params()).extracting(io.graphrag.model.EndpointParam::name, io.graphrag.model.EndpointParam::kind)
                .contains(tuple("body", io.graphrag.model.ParamKind.BODY));
        BodyShape fluxShape = result.bodyShapes().get("com.x.Dto");
        assertThat(fluxShape).isNotNull();
        assertThat(fluxShape.fields()).extracting(BodyShape.BodyField::name).contains("title", "score");
        assertThat(fluxShape.collection()).as("bodyToFlux must register collection=true").isTrue();

        // bodyToMono route (/item): shape collection must stay false (only one BodyShape per FQN key —
        // flux wins since it writes collection=true; mono would write false, so ensure flux overwrites)
        io.graphrag.model.Endpoint monoEp = result.endpoints().stream()
                .filter(e -> e.path().equals("/item")).findFirst().orElseThrow();
        assertThat(monoEp.params()).extracting(io.graphrag.model.EndpointParam::name, io.graphrag.model.EndpointParam::kind)
                .contains(tuple("body", io.graphrag.model.ParamKind.BODY));
    }

    @Test
    void index_bodyToMono_collectionIsFalse(@TempDir Path dir) throws Exception {  // REQ-002 bodyToMono — Fix A
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package com.x;\npublic record Dto(String name) {}\n");
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().POST(\"/item\", h::create).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> create(ServerRequest req) {\n"
              + "    return req.bodyToMono(Dto.class).flatMap(d -> ServerResponse.ok().build());\n"
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);

        BodyShape shape = result.bodyShapes().get("com.x.Dto");
        assertThat(shape).isNotNull();
        assertThat(shape.collection()).as("bodyToMono must keep collection=false").isFalse();
    }

    @Test
    void index_backExtractsPathPlaceholders_whenHandlerSkipsPathVariable(@TempDir Path dir) throws Exception {  // REQ-002 Fix B
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().GET(\"/sessions/{id}\", h::get).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> get(ServerRequest req) {\n"
              + "    return ServerResponse.ok().build();\n"  // no req.pathVariable("id") call
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);

        assertThat(result.endpoints()).hasSize(1);
        io.graphrag.model.Endpoint ep = result.endpoints().get(0);
        assertThat(ep.path()).isEqualTo("/sessions/{id}");
        assertThat(ep.params())
                .extracting(io.graphrag.model.EndpointParam::name, io.graphrag.model.EndpointParam::kind)
                .as("back-extracted placeholder 'id' must appear as PATH param")
                .contains(tuple("id", io.graphrag.model.ParamKind.PATH));
    }

    @Test
    void index_syntheticBody_notAddedWhenPathPlaceholderExists(@TempDir Path dir) throws Exception {  // REQ-002 Fix B ordering
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r(Handler h) {\n"
              + "    return route().POST(\"/sessions/{id}\", h::update).build();\n"
              + "  }\n}\n");
        Files.writeString(pkg.resolve("Handler.java"),
                "package com.x;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import reactor.core.publisher.Mono;\n"
              + "public class Handler {\n"
              + "  Mono<ServerResponse> update(ServerRequest req) {\n"
              + "    return ServerResponse.ok().build();\n"  // no pathVariable or bodyToMono
              + "  }\n}\n");

        IndexResult result = new RouterFunctionIndexer().index(dir);

        io.graphrag.model.Endpoint ep = result.endpoints().get(0);
        // PATH param should exist from back-extraction
        assertThat(ep.params())
                .extracting(io.graphrag.model.EndpointParam::kind)
                .as("back-extracted PATH param must be present")
                .contains(io.graphrag.model.ParamKind.PATH);
        // Synthetic body should NOT be added because PATH param is present (satisfies the guard)
        long bodyCount = ep.params().stream()
                .filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY
                        && p.javaType().equals("io.graphrag.synthetic.Body"))
                .count();
        assertThat(bodyCount).as("no synthetic body when PATH param exists from back-extraction").isZero();
    }

    @Test
    void index_paramsAreSorted_pathBeforeBody(@TempDir Path dir) throws Exception {  // REQ-002 Fix C
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Dto.java"),
                "package com.x;\npublic record Dto(String value) {}\n");
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
        List<EndpointParam> params = ep.params();
        assertThat(params).hasSizeGreaterThanOrEqualTo(2);
        int pathIdx = -1, bodyIdx = -1;
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).kind() == ParamKind.PATH) pathIdx = i;
            if (params.get(i).kind() == ParamKind.BODY) bodyIdx = i;
        }
        assertThat(pathIdx).as("PATH param must exist").isNotNegative();
        assertThat(bodyIdx).as("BODY param must exist").isNotNegative();
        assertThat(pathIdx).as("PATH must come before BODY in sorted order").isLessThan(bodyIdx);
    }

    @Test
    void index_unresolvedBodyPost_getsSyntheticShapeForExplore(@TempDir Path dir) throws Exception {  // REQ-003
        Path pkg = Files.createDirectories(dir.resolve("com/x"));
        Files.writeString(pkg.resolve("Routes.java"),
                "package com.x;\n"
              + "import org.springframework.context.annotation.Bean;\n"
              + "import org.springframework.web.reactive.function.server.*;\n"
              + "import static org.springframework.web.reactive.function.server.RouterFunctions.route;\n"
              + "public class Routes {\n"
              + "  @Bean RouterFunction<ServerResponse> r() {\n"
              + "    return route().POST(\"/sessions\", req -> ServerResponse.ok().build()).build();\n"
              + "  }\n}\n");   // 람다 handler — body 타입 해석 불가, path var 없음

        IndexResult result = new RouterFunctionIndexer().index(dir);
        io.graphrag.model.Endpoint ep = result.endpoints().get(0);
        assertThat(ep.params()).extracting(p -> p.kind()).contains(io.graphrag.model.ParamKind.BODY);
        BodyShape synth = result.bodyShapes().get("io.graphrag.synthetic.Body");
        assertThat(synth).isNotNull();
        assertThat(synth.fields()).isEmpty();
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
