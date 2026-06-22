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
import io.graphrag.model.Outcome;
import io.graphrag.model.ParamKind;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-006 프로덕션 배선: Generator가 GraphRagClient의 영속된 에러 계약 디스크립터를
 * FixtureComposer로 전달하여, 실제 생성된 테스트 소스에 에러 계약 단언이 나타난다.
 */
class GeneratorErrorContractEndToEndTest {

    private static final Endpoint EP = new Endpoint("get-api-items-by-id", "GET", "/api/items/{id}",
            "x.ItemController", "get",
            List.of(new EndpointParam("id", "java.lang.String", ParamKind.PATH)), false);

    /** envelope SUT: 200-wrapped 에러 body에 errorCode/errorDetail 필드 존재. */
    private static ExploredPath envelopeFailurePath() {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("errorCode", "404");
        response.put("errorDetail", "io.example.BizException: resource not found");
        return new ExploredPath(
                "get-api-items-by-id-404", "get-api-items-by-id",
                Json.mapper().createObjectNode().put("id", "42"),
                404, response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.FAILURE, 404, "404");
    }

    /** StatusOnly SUT: 에러 body에 errorCode/errorDetail 필드 없음(평범한 404 메시지만). */
    private static ExploredPath statusOnlyFailurePath() {
        ObjectNode response = Json.mapper().createObjectNode();
        response.put("message", "not found");
        return new ExploredPath(
                "get-api-items-by-id-404", "get-api-items-by-id",
                Json.mapper().createObjectNode().put("id", "42"),
                404, response,
                List.of(), List.of(), List.of(), "fuzzer",
                List.of(), List.of(), List.of(),
                List.of(), java.util.Map.of(),
                Outcome.Kind.FAILURE, 404, "404");
    }

    /** 영속된 에러 계약 디스크립터가 있는 envelope SUT graph → 생성 소스에 두 단언이 포함 */
    private static GraphRagClient envelopeClient() {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return EP; }
            public ExploredPath path(String id) { return envelopeFailurePath(); }
            public List<ExploredPath> pathsForEndpoint(String e) { return List.of(envelopeFailurePath()); }
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
            public String errorContractStatusField() { return "errorCode"; }
            public String errorDetailField() { return "errorDetail"; }
            public String errorDetailContains() { return "BizException"; }
        };
    }

    /** 비-envelope SUT graph(세 필드 null) → 회귀 없음: 에러 계약 단언 없음 */
    private static GraphRagClient statusOnlyClient() {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return EP; }
            public ExploredPath path(String id) { return statusOnlyFailurePath(); }
            public List<ExploredPath> pathsForEndpoint(String e) { return List.of(statusOnlyFailurePath()); }
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
    void envelopeSut_failurePath_generatesErrorContractAssertions() {
        GenerationRequest req = new GenerationRequest(
                "get-api-items-by-id", "get-api-items-by-id-404", "ItemErrorTest", "io.x", AuthMode.DISABLED);
        GenerationResult result = new Generator(envelopeClient()).generate(req);

        String source = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .reduce("", String::concat);

        assertThat(source)
                .as("errorCode equalTo string matcher")
                .contains(".body(\"errorCode\", equalTo(\"404\"))");
        assertThat(source)
                .as("errorDetail containsString matcher (FQN)")
                .contains("org.hamcrest.Matchers.containsString(\"BizException\")");
    }

    @Test
    void statusOnlySut_failurePath_noErrorContractAssertions() {
        GenerationRequest req = new GenerationRequest(
                "get-api-items-by-id", "get-api-items-by-id-404", "ItemErrorTest", "io.x", AuthMode.DISABLED);
        GenerationResult result = new Generator(statusOnlyClient()).generate(req);

        String source = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(io.graphrag.model.GeneratedFile::content)
                .reduce("", String::concat);

        assertThat(source)
                .as("no error-envelope assertion for status-only SUT")
                .doesNotContain(".body(\"errorCode\"")
                .doesNotContain("containsString(\"BizException\")");
    }
}
