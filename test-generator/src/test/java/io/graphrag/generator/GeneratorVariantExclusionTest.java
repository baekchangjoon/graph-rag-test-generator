package io.graphrag.generator;

import io.graphrag.generator.client.GraphRagClient;
import io.graphrag.model.AuthMode;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.GenerationRequest;
import io.graphrag.model.GenerationResult;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009 sub-criterion 3: Generator が response-variant discoveredBy を持つ path を
 * 生성対象から除外する(마커 rename 후 제외 필터 동작 검증).
 *
 * <p>Generator.java line 81: {@code "response-variant".equals(p.discoveredBy())} 분기를
 * 직접 행사한다. normal path(heuristic)는 생성 포함, response-variant path는 생성 제외됨을
 * 파일 수와 생성된 메서드명으로 단언한다.
 */
class GeneratorVariantExclusionTest {

    private static final Endpoint EP = new Endpoint("post-api-orders", "POST", "/api/orders",
            "x.OrderController", "create", List.of(), true);

    /** discoveredBy="heuristic" — 정상 탐색 경로(생성 포함 대상). */
    private static ExploredPath normalPath() {
        return new ExploredPath("post-api-orders-happy", "post-api-orders",
                Json.mapper().createObjectNode(), 200, Json.mapper().createObjectNode(),
                List.of(), List.of(), List.of(), "heuristic", List.of(), List.of(), List.of());
    }

    /** discoveredBy="response-variant" — String 리터럴 변형 루프가 생성한 경로(생성 제외 대상). */
    private static ExploredPath responseVariantPath() {
        return new ExploredPath("post-api-orders-responsevar-region-EMBARGOED", "post-api-orders",
                Json.mapper().createObjectNode(), 422, Json.mapper().nullNode(),
                List.of(), List.of(), List.of(), "response-variant", List.of(), List.of(), List.of());
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

    /**
     * response-variant path 단독 입력 → 생성 파일 0건.
     * 필터가 제거하면 paths 리스트가 비어 생성물이 없어야 한다.
     */
    @Test
    void responseVariantPathAlone_producesNoFiles() {
        GenerationRequest req = new GenerationRequest(
                "post-api-orders", null, "OrdersCreateTest", "io.x", AuthMode.REAL);
        GenerationResult result = new Generator(client(List.of(responseVariantPath()))).generate(req);

        assertThat(result.files())
                .as("response-variant 경로만 있으면 생성 파일이 없어야 한다")
                .isEmpty();
    }

    /**
     * normal path 1개 + response-variant path 1개 혼합 입력:
     * - 생성된 .java 파일은 정확히 1개 (normal path 기반).
     * - 생성된 파일에 response-variant 경로에서 파생된 메서드("responsevar_region_EMBARGOED")가 없다.
     * - 생성된 파일에 normal path에서 파생된 메서드("happy")가 있다.
     */
    @Test
    void responseVariantPath_excludedFromGeneration_normalPathIncluded() {
        GenerationRequest req = new GenerationRequest(
                "post-api-orders", null, "OrdersCreateTest", "io.x", AuthMode.REAL);
        GenerationResult result =
                new Generator(client(List.of(normalPath(), responseVariantPath()))).generate(req);

        // .java 파일 수: normal path 1개만 생성되어야 한다 (+ junit-platform.properties 는 .java 아님)
        List<io.graphrag.model.GeneratedFile> javaFiles = result.files().stream()
                .filter(f -> f.relativePath().endsWith(".java"))
                .toList();
        assertThat(javaFiles)
                .as("normal path 1개만 생성 → .java 파일 정확히 1개")
                .hasSize(1);

        // normal path 기반 메서드("happy")가 생성 내용에 포함돼야 한다
        String generatedSource = javaFiles.get(0).content();
        assertThat(generatedSource)
                .as("normal path(happy) 기반 메서드가 생성 내용에 포함돼야 한다")
                .contains("happy");

        // response-variant 경로에서 파생된 메서드명 fragment가 생성 내용에 없어야 한다
        assertThat(generatedSource)
                .as("response-variant 경로(responsevar_region_EMBARGOED)가 생성 내용에서 제외돼야 한다")
                .doesNotContain("responsevar_region_EMBARGOED");
    }
}
