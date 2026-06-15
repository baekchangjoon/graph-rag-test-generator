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

/** @Controller 폼(ParamKind.FORM) 엔드포인트는 생성 미지원 — 커버리지 전용으로 스킵된다. */
class GeneratorFormSkipTest {

    private static final Endpoint FORM_EP = new Endpoint("post-web-orders", "POST", "/web/orders",
            "x.OrderWebController", "submit",
            List.of(new EndpointParam("form", "x.OrderWebController$OrderForm", ParamKind.FORM)), true);

    private static ExploredPath okPath() {
        return new ExploredPath("post-web-orders-okarm", "post-web-orders",
                Json.mapper().createObjectNode(), 302, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());
    }

    private static GraphRagClient client() {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return FORM_EP; }
            public ExploredPath path(String id) { return okPath(); }
            public List<ExploredPath> pathsForEndpoint(String e) { return List.of(okPath()); }
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
    void formEndpoint_endpointLevelRequest_skipped() {
        GenerationRequest req = new GenerationRequest(
                "post-web-orders", null, "WebOrdersTest", "io.x", AuthMode.REAL);
        GenerationResult result = new Generator(client()).generate(req);

        assertThat(result.files()).isEmpty();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("form endpoint not generated"));
    }

    @Test
    void formEndpoint_pathLevelRequest_alsoSkipped() {
        // pathId 지정(path별 생성) 경로도 FORM 가드보다 먼저 차단되어야 한다 — generateSingle의 JSON-body
        // 가정이 폼에 깨진 테스트를 내는 것 방지.
        GenerationRequest req = new GenerationRequest(
                "post-web-orders", "post-web-orders-okarm", "WebOrdersTest", "io.x", AuthMode.REAL);
        GenerationResult result = new Generator(client()).generate(req);

        assertThat(result.files()).isEmpty();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("form endpoint not generated"));
    }
}
