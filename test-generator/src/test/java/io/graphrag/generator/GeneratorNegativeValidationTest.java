package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** negative-validation path(제약 위반 거부 arm 커버용)는 테스트 생성에서 제외된다(커버리지 전용). */
class GeneratorNegativeValidationTest {

    private static final Endpoint EP = new Endpoint("post-api-signups", "POST", "/api/signups",
            "x.SignupController", "signup",
            List.of(new EndpointParam("req", "x.SignupController$SignupRequest", ParamKind.BODY)), false);

    private static ExploredPath happy() {
        return new ExploredPath("post-api-signups-happy", "post-api-signups",
                Json.mapper().createObjectNode(), 201, Json.mapper().createObjectNode(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());
    }

    private static ExploredPath negativeValidation() {
        return new ExploredPath("post-api-signups-negval-email-email", "post-api-signups",
                Json.mapper().createObjectNode(), 400, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "negative-validation", List.of(), List.of(), List.of());
    }

    private static GraphRagClient client(List<ExploredPath> paths) {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return EP; }
            public ExploredPath path(String id) {
                return paths.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            }
            public List<ExploredPath> pathsForEndpoint(String e) { return paths; }
            public List<CapturedSql> sqlForPath(String p) { return List.of(); }
            public List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String p) { return List.of(); }
            public boolean hasWsEndpoint(String id) { return false; }
            public io.graphrag.model.WsEndpoint wsEndpoint(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.WsExchange> wsExchangesFor(String w) { return List.of(); }
            public io.graphrag.model.WsExchange wsExchange(String id) { throw new UnsupportedOperationException(); }
            public boolean hasKafkaConsumer(String id) { return false; }
            public io.graphrag.model.KafkaConsumer kafkaConsumer(String id) { throw new UnsupportedOperationException(); }
            public List<io.graphrag.model.KafkaExchange> kafkaExchangesFor(String c) { return List.of(); }
            public List<io.graphrag.model.TableSchema> tables() { return List.of(); }
            public List<io.graphrag.model.RequiredSeed> seedsForPath(String p) { return List.of(); }
        };
    }

    @Test
    void skipsNegativeValidationPath_generatesOnlyHappy() {
        GenerationRequest req = new GenerationRequest(
                "post-api-signups", null, "SignupTest", "io.x", AuthMode.DISABLED);
        GenerationResult result = new Generator(client(List.of(happy(), negativeValidation()))).generate(req);

        // negative-validation path는 제외 → happy 클래스 1개만 생성 (+ junit-platform.properties)
        assertThat(result.files()).filteredOn(f -> f.relativePath().endsWith(".java")).hasSize(1);
        assertThat(result.files()).filteredOn(f -> f.relativePath().endsWith(".java"))
                .allSatisfy(f -> assertThat(f.relativePath()).contains("SignupTest"));
        assertThat(result.files()).noneSatisfy(f ->
                assertThat(f.relativePath()).containsIgnoringCase("negval"));
    }
}
