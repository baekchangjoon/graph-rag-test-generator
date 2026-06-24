package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GeneratedFile;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-008/009: Generator가 discoveredBy="egress-assertion" 경로를 생성 대상에 포함하고,
 * CONTRACT provenance의 CapturedHttpCall responseBody가 생성된 소스에 방출되는지 검증 (회귀 가드).
 *
 * <p>Generator.java 79-81: 제외 목록은 negative-auth/negative-validation/response-variant뿐.
 * egress-assertion은 목록에 없으므로 생성에 포함되어야 한다.
 */
class GeneratorEgressAssertionTest {

    private static final Endpoint EP = new Endpoint("get-inventory-stock", "GET", "/inventory/stock",
            "x.InventoryController", "getStock", List.of(), true);

    /**
     * discoveredBy="egress-assertion" — egress 발견 경로(생성 포함 대상).
     * httpCallsForPath에서 CONTRACT body를 가진 CapturedHttpCall을 반환하므로
     * 생성된 소스의 mock 블록에 "EMBARGOED"가 포함된다.
     */
    private static ExploredPath egressAssertionPath() {
        return new ExploredPath("get-inventory-stock-egress", "get-inventory-stock",
                Json.mapper().createObjectNode(), 200, Json.mapper().createObjectNode(),
                List.of(), List.of(), List.of(), "egress-assertion", List.of(), List.of(), List.of());
    }

    /** discoveredBy="response-variant" — 생성 제외 대상. */
    private static ExploredPath responseVariantPath() {
        return new ExploredPath("get-inventory-stock-responsevar-region-EMBARGOED", "get-inventory-stock",
                Json.mapper().createObjectNode(), 422, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "response-variant", List.of(), List.of(), List.of());
    }

    /** CONTRACT body를 가진 CapturedHttpCall: stubBody가 그대로 방출됨. */
    private static CapturedHttpCall contractCall() {
        return new CapturedHttpCall("h1", "get-inventory-stock-egress", "GET", "/external/inventory",
                Map.of(), null, 200, "{\"region\":\"EMBARGOED\",\"mode\":\"BACKORDER\"}",
                List.of(), false, CapturedHttpCall.Provenance.CONTRACT);
    }

    private static GraphRagClient client(List<ExploredPath> paths) {
        return new GraphRagClient() {
            public Endpoint endpoint(String id) { return EP; }
            public ExploredPath path(String id) {
                return paths.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            }
            public List<ExploredPath> pathsForEndpoint(String e) { return paths; }
            public List<CapturedSql> sqlForPath(String p) { return List.of(); }
            public List<CapturedHttpCall> httpCallsForPath(String p) {
                if ("get-inventory-stock-egress".equals(p)) {
                    return List.of(contractCall());
                }
                return List.of();
            }
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

    /**
     * egress-assertion path 1개 + response-variant path 1개 혼합 입력:
     * - egress-assertion 경로는 생성 포함(파일 비어있지 않음).
     * - 생성된 소스에 CONTRACT body의 "EMBARGOED"가 있다(mock stubBody 그대로 방출).
     * - response-variant 경로("responsevar")는 여전히 제외된다.
     */
    @Test
    void egressAssertionPath_isGenerated_responseVariantStillExcluded() {
        GenerationRequest req = new GenerationRequest(
                "get-inventory-stock", null, "InventoryEgressTest", "io.x", AuthMode.REAL);
        GenerationResult result =
                new Generator(client(List.of(egressAssertionPath(), responseVariantPath()))).generate(req);
        String allSource = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .map(GeneratedFile::content).collect(Collectors.joining("\n"));
        assertThat(result.files()).isNotEmpty();
        assertThat(allSource).contains("EMBARGOED");
        assertThat(allSource).doesNotContain("responsevar");
    }
}
