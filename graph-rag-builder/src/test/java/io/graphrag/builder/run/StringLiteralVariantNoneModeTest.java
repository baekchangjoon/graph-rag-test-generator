package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.NoTraceKey;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.run.ResponseFieldVariantGenerator.VariantPlan;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-012: none 모드 String 변형 순차 교체.
 *
 * <p>String 필드를 가진 응답 형상에서 buildVariantCandidates가 추출한 String 리터럴 후보가
 * --trace-mode none(NoTraceKey)에서 enum 변형과 동일하게 순차 교체로 동작하는지 검증한다.
 * <ul>
 *   <li>각 invoke 시점: 그 변형의 String 값만 활성(헤더 없는 요청에 변형 응답).</li>
 *   <li>루프 종료 후: 변형 stub 제거 → 전역(baseline) stub 복원.</li>
 *   <li>buildVariantCandidates로 String 후보가 올바르게 추출되는지 단언(REQ-012 직접 경로 검증).</li>
 * </ul>
 */
class StringLiteralVariantNoneModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static final String CLASS = "io/graphrag/sample/orders/OrderController";

    /**
     * 응답 형상: orderId(Integer) + status(java.lang.String).
     * stage-1 기본값: status → "sample-status" (ShapeJsonSynthesizer 규칙).
     */
    private static final BodyShape ORDER_SHAPE = new BodyShape("OrderResponse", List.of(
            new BodyShape.BodyField("orderId", "Integer"),
            new BodyShape.BodyField("status", "java.lang.String")), false);

    /**
     * status 필드에 대한 String 리터럴: "PENDING","SHIPPED","DELIVERED".
     * 모두 baseline("sample-status")과 달라 후보에 포함된다.
     * dtoFqn "OrderResponse" → field "status" 매핑.
     */
    private static final Map<String, Map<String, List<String>>> STRING_LITERALS = Map.of(
            "OrderResponse", Map.of(
                    "status", List.of("PENDING", "SHIPPED", "DELIVERED")));

    /** enum 없음 — 순수 String 변형 시나리오. */
    private static final Map<String, List<String>> NO_ENUMS = Map.of();

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer(new NoTraceKey());
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(NO_ENUMS), new NoTraceKey());
    }

    private static JsonNode baseline() throws Exception {
        return MAPPER.readTree("{\"orderId\":1,\"status\":\"sample-status\"}");
    }

    private static ExecutionDataStore deltaWithArm(int armBit) {
        boolean[] probes = new boolean[8];
        probes[armBit] = true;
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(456L, CLASS, probes));
        return store;
    }

    /**
     * buildVariantCandidates가 String 필드의 non-baseline 리터럴 3건을 올바르게 추출한다.
     */
    @Test
    void buildVariantCandidatesExtractsStringLiterals() {
        ShapeJsonSynthesizer shapes = new ShapeJsonSynthesizer(NO_ENUMS);
        Map<String, List<String>> candidates =
                EndpointExplorationRunner.buildVariantCandidates(ORDER_SHAPE, NO_ENUMS, STRING_LITERALS, shapes);

        // orderId(Integer)는 후보 없음, status(String)는 3건
        assertThat(candidates).containsOnlyKeys("status");
        assertThat(candidates.get("status"))
                .containsExactlyInAnyOrder("PENDING", "SHIPPED", "DELIVERED")
                .doesNotContain("sample-status");   // baseline 제외
    }

    /**
     * none 모드에서 String 변형이 순차 교체(removeVariant)로 동작한다:
     * - 각 invoke 시점에 그 변형의 status 값만 응답.
     * - 루프 종료 후 변형 stub 제거 → 전역 baseline 복원.
     * - attempted == 후보 수(3).
     */
    @Test
    void noneModeSequentiallyReplacesStringVariantsAndPreservesGlobal() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/orders/status", ORDER_SHAPE);   // 전역 baseline: status="sample-status"

        // buildVariantCandidates를 통해 String 리터럴 후보 추출 — production 경로 검증.
        ShapeJsonSynthesizer shapes = new ShapeJsonSynthesizer(NO_ENUMS);
        Map<String, List<String>> candidates =
                EndpointExplorationRunner.buildVariantCandidates(ORDER_SHAPE, NO_ENUMS, STRING_LITERALS, shapes);
        VariantPlan plan = new ResponseFieldVariantGenerator().generate(candidates, 32);
        assertThat(plan.kept()).isNotEmpty();   // 후보가 plan에 반영됐는지 사전 확인

        ExecutionDataStore cumulative = new ExecutionDataStore();

        // invoke 시점마다 활성 변형 stub의 응답을 캡처한다(헤더 없는 요청 → none 모드에서 변형 stub이 전역보다 우선).
        List<String> servedAtInvoke = new java.util.ArrayList<>();
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            @Override public String nextTraceId() { return null; }   // none: trace-id 없음
            @Override public ExecutionDataStore invoke(JsonNode body) {
                try {
                    HttpResponse<String> resp = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/orders/status"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    servedAtInvoke.add(resp.body());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                // 각 변형에서 서로 다른 arm이 열리는 것처럼 시뮬레이션 (armBit = invocation 순서 기반)
                int arm = servedAtInvoke.size() - 1;
                return deltaWithArm(arm);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/orders/status", syn,
                        false, invoker, cumulative, Set.of(CLASS));

        // 각 invoke에서 그 변형의 status 값이 응답됐다(순차 교체 — 한 번에 변형 하나만 활성).
        // plan 순서: TreeMap(status→sorted["DELIVERED","PENDING","SHIPPED"]) → 알파벳순.
        // get(0)=DELIVERED, get(1)=PENDING, get(2)=SHIPPED.
        assertThat(servedAtInvoke).hasSize(3);
        // invoke 0: DELIVERED만 활성, PENDING·SHIPPED·baseline은 없다.
        assertThat(servedAtInvoke.get(0))
                .contains("DELIVERED")
                .doesNotContain("PENDING")
                .doesNotContain("SHIPPED")
                .doesNotContain("sample-status");
        // invoke 1: PENDING만 활성.
        assertThat(servedAtInvoke.get(1))
                .contains("PENDING")
                .doesNotContain("DELIVERED")
                .doesNotContain("SHIPPED")
                .doesNotContain("sample-status");
        // invoke 2: SHIPPED만 활성.
        assertThat(servedAtInvoke.get(2))
                .contains("SHIPPED")
                .doesNotContain("DELIVERED")
                .doesNotContain("PENDING")
                .doesNotContain("sample-status");

        // 루프 종료 후: 변형 stub은 모두 제거 → 헤더 없는 요청은 전역 baseline 응답.
        assertThat(syn.isVariantRegistered("GET", "/orders/status")).isFalse();
        HttpResponse<String> after = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/orders/status"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(after.body()).contains("sample-status");   // 전역 baseline 복원

        // attempted == plan의 변형 수 (DELIVERED, PENDING, SHIPPED 3건).
        assertThat(result.attempted()).isEqualTo(plan.kept().size());
    }
}
