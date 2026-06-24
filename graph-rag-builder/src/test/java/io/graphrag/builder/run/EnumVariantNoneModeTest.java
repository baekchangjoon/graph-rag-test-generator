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
 * REQ-009, REQ-010: none 모드 변형 탐색 — trace-id 격리 불가 → 변형 stub 순차 교체.
 * 각 변형 invoke 시점에 그 변형 stub만 활성(앞 변형은 removeVariant로 제거)이고,
 * 루프 종료 후 전역(단계1) stub은 보존되어 헤더 없는 요청이 baseline 응답을 받는다.
 */
class EnumVariantNoneModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static final String CLASS = "io/graphrag/sample/orders/OrderController";

    private static final BodyShape INV_SHAPE = new BodyShape("Inv", List.of(
            new BodyShape.BodyField("available", "Integer"),
            new BodyShape.BodyField("mode", "p.FulfillmentMode")), false);

    private static final Map<String, List<String>> ENUMS = Map.of(
            "p.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER"));

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer(new NoTraceKey());
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(ENUMS), new NoTraceKey());
    }

    private static JsonNode baseline() throws Exception {
        return MAPPER.readTree("{\"available\":1,\"mode\":\"STANDARD\"}");
    }

    private static ExecutionDataStore deltaWithArm(int armBit) {
        boolean[] probes = new boolean[8];
        probes[armBit] = true;
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(123L, CLASS, probes));
        return store;
    }

    @Test
    void noneModeSequentiallyReplacesVariantsAndPreservesGlobal() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", INV_SHAPE);   // 전역 baseline STANDARD

        // 호출자 책임: baseline(선언순 첫 상수) 제외한 enum 후보 맵을 직접 구성.
        // FulfillmentMode {STANDARD(baseline),EXPRESS_ONLY,BACKORDER} → non-baseline {EXPRESS_ONLY,BACKORDER}
        Map<String, List<String>> enumCandidates = new java.util.TreeMap<>();
        enumCandidates.put("mode", List.of("EXPRESS_ONLY", "BACKORDER"));   // STANDARD baseline 제외
        VariantPlan plan = new ResponseFieldVariantGenerator().generate(enumCandidates, 32);
        ExecutionDataStore cumulative = new ExecutionDataStore();

        // none 모드: invoke 시점에 변형 stub이 활성(헤더 없는 요청에 변형 응답), 직전 변형은 제거됐어야 함.
        List<String> servedAtInvoke = new java.util.ArrayList<>();
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            private int n;
            @Override public String nextTraceId() { return null; }   // none: trace-id 없음
            @Override public ExecutionDataStore invoke(JsonNode body) {
                try {
                    // 헤더 없는 요청 → 현재 활성 변형 stub이 응답해야 한다(전역보다 우선 priority).
                    HttpResponse<String> resp = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(server.baseUrl() + "/inventory/stock")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    servedAtInvoke.add(resp.body());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return deltaWithArm(body.get("mode").asText().equals("BACKORDER") ? 2 : 3);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreEnumResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        false, invoker, cumulative, Set.of(CLASS));

        // 각 invoke에서 그 변형의 mode가 응답됐다(순차 교체 — 한 번에 변형 하나만 활성).
        assertThat(servedAtInvoke.get(0)).contains("BACKORDER").doesNotContain("EXPRESS_ONLY");
        assertThat(servedAtInvoke.get(1)).contains("EXPRESS_ONLY").doesNotContain("BACKORDER");

        // 루프 종료 후 변형 stub은 모두 제거 → 헤더 없는 요청은 전역(STANDARD) baseline 복원.
        assertThat(syn.isVariantRegistered("GET", "/inventory/stock")).isFalse();
        HttpResponse<String> after = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/inventory/stock")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(after.body()).contains("STANDARD");
        assertThat(result.attempted()).isEqualTo(2);
    }
}
