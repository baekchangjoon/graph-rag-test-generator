package io.graphrag.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.EndpointParam;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import io.graphrag.model.Outcome;
import io.graphrag.model.ParamKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-A/D 프로덕션 배선: Generator가 AssertionProvenanceUpgrader를 실제로 호출해,
 * 생성된 테스트 소스에 승격된 매처가 나타나는지 — 그리고 404 read(요청 변형 path)에서는
 * message 승격이 배선 수준에서 스킵되는지 — 를 렌더 결과 문자열로 고정한다.
 */
class GeneratorAssertionProvenanceWiringTest {

    private static Endpoint postEndpoint() {
        return new Endpoint("post-api-things", "POST", "/api/things", "x.ThingController", "create",
                List.of(), false, null, List.of("name is required"));
    }

    private static Endpoint getByIdEndpoint() {
        return new Endpoint("get-api-things-id", "GET", "/api/things/{id}", "x.ThingController", "get",
                List.of(new EndpointParam("id", "java.lang.Long", ParamKind.PATH)), false, null,
                List.of(" not found"));
    }

    private static ExploredPath postFailurePath() {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("timestamp", "2026-09-01T00:00:00.000+00:00");
        response.put("status", 422);
        response.put("error", "Unprocessable Entity");
        response.put("path", "/api/things");
        response.put("message", "name is required");
        return new ExploredPath(
                "post-api-things-422", "post-api-things",
                Json.mapper().createObjectNode().put("name", ""),
                422, response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.FAILURE, 422, "422");
    }

    private static ExploredPath get404Path() {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("timestamp", "2026-09-01T00:00:00.000+00:00");
        response.put("status", 404);
        response.put("error", "Not Found");
        response.put("path", "/api/things/42");
        response.put("message", "thing 42 not found");
        return new ExploredPath(
                "get-api-things-id-404", "get-api-things-id",
                Json.mapper().createObjectNode().put("id", "42"),
                404, response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.FAILURE, 404, "404");
    }

    private static GraphRagClient client(Endpoint ep, ExploredPath path) {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return ep; }
            public ExploredPath path(String id) { return path; }
            public List<ExploredPath> pathsForEndpoint(String e) { return List.of(path); }
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

    private static String render(Endpoint ep, ExploredPath path, String pathId) {
        GenerationRequest req = new GenerationRequest(
                ep.id(), pathId, "ThingTest", "io.x", AuthMode.DISABLED);
        GenerationResult result = new Generator(client(ep, path)).generate(req);
        return result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .reduce("", String::concat);
    }

    @Test
    void failureEnvelope_rendersUpgradedContractAndMessageAssertions() {
        String source = render(postEndpoint(), postFailurePath(), "post-api-things-422");

        assertThat(source)
                .contains(".body(\"status\", equalTo(422))")
                .contains(".body(\"error\", equalTo(\"Unprocessable Entity\"))")
                .contains(".body(\"path\", equalTo(\"/api/things\"))")
                .contains(".body(\"message\", equalTo(\"name is required\"))")
                .contains(".body(\"timestamp\", notNullValue())");
    }

    @Test
    void notFoundRead_messageStaysNotNull_requestMutatedWiredTrue() {
        // 404 read는 부재-id 센티널로 요청이 변형된다 → Generator가 requestMutated=true를 배선해
        // message는 승격되지 않고, arm 무관 계약(status/error)만 승격된다.
        String source = render(getByIdEndpoint(), get404Path(), "get-api-things-id-404");

        assertThat(source)
                .contains(".body(\"status\", equalTo(404))")
                .contains(".body(\"error\", equalTo(\"Not Found\"))")
                .contains(".body(\"message\", notNullValue())")
                .doesNotContain("containsString(\" not found\")");
    }
}
