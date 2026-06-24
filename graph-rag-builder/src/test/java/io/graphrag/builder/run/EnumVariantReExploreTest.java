package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.OtelTraceKey;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.run.ResponseFieldVariantGenerator.VariantPlan;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009, REQ-010: enum 변형 탐색 루프(otel/sleuth 격리 모드).
 * 각 변형 stub을 invoke 전 trace-id로 등록·invoke하고, per-variant 커버리지 delta를
 * cumulativeCoverage에 OR-병합한다. 새 arm을 연 변형은 보존되고, 앞선 변형이 연 arm은
 * 최종 cumulative에 남는다(리셋 금지). budget 소진까지 진행한다.
 *
 * <p>루프 자체는 {@link EndpointExplorationRunner#exploreResponseVariants} 정적 헬퍼로
 * 추출해 SUT/DB 없이 임베디드 HttpCaptureServer + 가짜 invoke로 검증한다.
 */
class EnumVariantReExploreTest {

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
        server = new HttpCaptureServer(new OtelTraceKey());
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(ENUMS), new OtelTraceKey());
    }

    private static JsonNode baseline() throws Exception {
        return MAPPER.readTree("{\"available\":1,\"mode\":\"STANDARD\"}");
    }

    /** 단일 클래스의 probe 벡터 1개를 가진 delta. armBit 위치만 켠다. */
    private static ExecutionDataStore deltaWithArm(int armBit) {
        boolean[] probes = new boolean[8];
        probes[armBit] = true;
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(123L, CLASS, probes));
        return store;
    }

    @Test
    void variantsOpeningNewArmsAreKeptAndCumulativeOrMerges() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        // 전역(단계1) stub + baseline arm: STANDARD가 probe[1]을 연다(누적 시작 상태).
        syn.register("GET", "/inventory/stock", INV_SHAPE);
        ExecutionDataStore cumulative = new ExecutionDataStore();
        cumulative.put(new ExecutionData(123L, CLASS, armBits(1)));

        VariantPlan plan = enumPlan(32);
        // kept = [mode=BACKORDER, mode=EXPRESS_ONLY] — 각각 다른 arm을 연다.
        List<JsonNode> registeredBodies = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();

        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            @Override
            public String nextTraceId() {
                return "trace00000000000" + seq.incrementAndGet();   // 16-hex-ish, distinct per invoke
            }

            @Override
            public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                registeredBodies.add(body);
                // BACKORDER → arm 2, EXPRESS_ONLY → arm 3.
                if (body.get("mode").asText().equals("BACKORDER")) {
                    return new EndpointExplorationRunner.VariantOutcome(deltaWithArm(2), 200);
                }
                return new EndpointExplorationRunner.VariantOutcome(deltaWithArm(3), 200);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        true, invoker, cumulative, Set.of(CLASS), Set.of());

        // 두 변형 모두 새 arm을 열었으므로 보존(kept=2).
        assertThat(result.keptVariantLabels()).containsExactlyInAnyOrder("mode=BACKORDER", "mode=EXPRESS_ONLY");
        // 등록된 변형 body는 baseline + override (available 보존, mode만 변형).
        assertThat(registeredBodies).allSatisfy(b -> assertThat(b.get("available").asInt()).isEqualTo(1));
        assertThat(registeredBodies).extracting(b -> b.get("mode").asText())
                .containsExactlyInAnyOrder("BACKORDER", "EXPRESS_ONLY");
        // cumulative OR-병합: baseline arm(1) + 두 변형 arm(2,3)이 모두 누적에 남는다.
        boolean[] merged = probesFor(cumulative, CLASS);
        assertThat(merged[1]).isTrue();
        assertThat(merged[2]).isTrue();
        assertThat(merged[3]).isTrue();
    }

    @Test
    void variantWithoutNewArmIsNotKeptButCumulativeStillMerges() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", INV_SHAPE);
        ExecutionDataStore cumulative = new ExecutionDataStore();
        cumulative.put(new ExecutionData(123L, CLASS, armBits(1, 2, 3)));   // 이미 모든 arm 누적

        VariantPlan plan = enumPlan(32);
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            private int n;
            @Override public String nextTraceId() { return "trace00000000000" + (++n); }
            @Override public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                return body.get("mode").asText().equals("BACKORDER")
                        ? new EndpointExplorationRunner.VariantOutcome(deltaWithArm(2), 200)
                        : new EndpointExplorationRunner.VariantOutcome(deltaWithArm(3), 200);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        true, invoker, cumulative, Set.of(CLASS), Set.of());

        // 두 변형 모두 새 arm 없음 → 보존 0.
        assertThat(result.keptVariantLabels()).isEmpty();
        // budget 만큼 시도는 했다.
        assertThat(result.attempted()).isEqualTo(2);
    }

    @Test
    void budgetConverges() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", INV_SHAPE);
        ExecutionDataStore cumulative = new ExecutionDataStore();
        // budget=1 → 단 1개 변형만 kept 목록으로 들어온다.
        VariantPlan plan = enumPlan(1);
        assertThat(plan.kept()).hasSize(1);

        AtomicInteger calls = new AtomicInteger();
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            private int n;
            @Override public String nextTraceId() { return "trace00000000000" + (++n); }
            @Override public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                calls.incrementAndGet();
                return new EndpointExplorationRunner.VariantOutcome(deltaWithArm(2), 200);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        true, invoker, cumulative, Set.of(CLASS), Set.of());

        assertThat(calls.get()).isEqualTo(1);   // budget 1 → 1회 invoke로 수렴
        assertThat(result.attempted()).isEqualTo(1);
    }

    @Test
    void closePendingIsCalledEvenWhenInvokeThrows() throws Exception {
        // #1/#2 scope 누수 가드: invoke가 던져도(예: http.send 실패) 루프는 변형마다 closePending을
        // finally로 호출해 nextTraceId가 연 scope를 drain한다. 호출 누락 시 OTLP 버퍼 누수.
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", INV_SHAPE);
        ExecutionDataStore cumulative = new ExecutionDataStore();

        VariantPlan plan = enumPlan(32);
        AtomicInteger nextTraceCalls = new AtomicInteger();
        AtomicInteger closePendingCalls = new AtomicInteger();

        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            @Override public String nextTraceId() {
                return "trace00000000000" + nextTraceCalls.incrementAndGet();
            }
            @Override public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                throw new RuntimeException("send failed");   // 변형 invoke가 항상 실패
            }
            @Override public void closePending() {
                closePendingCalls.incrementAndGet();
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        true, invoker, cumulative, Set.of(CLASS), Set.of());

        // 변형마다 closePending이 정확히 1회씩(invoke 실패해도) — scope drain 보장.
        assertThat(closePendingCalls.get()).isEqualTo(nextTraceCalls.get());
        assertThat(closePendingCalls.get()).isEqualTo(plan.kept().size());
        // best-effort: 모든 변형 실패했지만 회귀 아님(보존 0, 시도는 budget만큼).
        assertThat(result.keptVariantLabels()).isEmpty();
        assertThat(result.attempted()).isEqualTo(plan.kept().size());
    }

    @Test
    void closePendingIsCalledOnSuccessfulInvokeToo() throws Exception {
        // 성공 경로에서도 closePending이 변형마다 불린다(invoke가 scope를 소비했으면 no-op).
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", INV_SHAPE);
        ExecutionDataStore cumulative = new ExecutionDataStore();

        VariantPlan plan = enumPlan(32);
        AtomicInteger closePendingCalls = new AtomicInteger();
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            private int n;
            @Override public String nextTraceId() { return "trace00000000000" + (++n); }
            @Override public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                return new EndpointExplorationRunner.VariantOutcome(deltaWithArm(2), 200);
            }
            @Override public void closePending() { closePendingCalls.incrementAndGet(); }
        };

        EndpointExplorationRunner.exploreResponseVariants(
                plan, baseline(), "GET", "/inventory/stock", syn,
                true, invoker, cumulative, Set.of(CLASS), Set.of());

        assertThat(closePendingCalls.get()).isEqualTo(plan.kept().size());
    }

    /**
     * REQ-F012-018: envelope-sourced 변형은 coverage-delta 무관 항상 보존된다.
     *
     * <p>fake invoker가 새 arm 없음(빈 delta)을 반환해도, envelopeFields에 속한 필드를
     * override하는 변형은 keptVariants에 포함된다. 반면 envelopeFields에 없는 필드를
     * override하는 non-envelope 변형은 새 arm이 없으면 버려진다.
     */
    @Test
    void envelopeVariantIsKeptEvenWithNoNewArm_nonEnvelopeVariantWithNoNewArmIsDropped() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/pricing/quote", new BodyShape("PricingDto", List.of(
                new BodyShape.BodyField("errorCode", "java.lang.String"),
                new BodyShape.BodyField("mode", "java.lang.String")), false));
        // cumulative가 이미 모든 arm을 포함 → mergeAndDetectNewArm은 항상 false 반환.
        ExecutionDataStore cumulative = new ExecutionDataStore();
        cumulative.put(new ExecutionData(123L, CLASS, armBits(1, 2, 3, 4, 5)));

        // plan: errorCode=ERROR(envelope 출처) + mode=BACKORDER(non-envelope).
        Map<String, List<String>> candidates = new java.util.TreeMap<>();
        candidates.put("errorCode", List.of("ERROR"));     // envelope-sourced
        candidates.put("mode", List.of("BACKORDER"));      // non-envelope
        VariantPlan plan = new ResponseFieldVariantGenerator().generate(candidates, 32);

        // invoker: 항상 새 arm 없음(빈 delta).
        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            private int n;
            @Override public String nextTraceId() { return "trace00000000000" + (++n); }
            @Override public EndpointExplorationRunner.VariantOutcome invoke(JsonNode body) {
                return new EndpointExplorationRunner.VariantOutcome(new ExecutionDataStore(), 200);
            }
        };

        // envelopeFields = {"errorCode"} → errorCode 포함 변형은 unconditional keep.
        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, MAPPER.readTree("{\"errorCode\":\"sample\",\"mode\":\"STANDARD\"}"),
                        "GET", "/pricing/quote", syn,
                        true, invoker, cumulative, Set.of(CLASS), Set.of("errorCode"));

        // errorCode=ERROR 변형은 새 arm 없어도 보존된다.
        assertThat(result.keptVariantLabels())
                .as("envelope 변형(errorCode=ERROR)은 coverage-delta 무관 항상 보존돼야 한다")
                .contains("errorCode=ERROR");

        // mode=BACKORDER 변형: non-envelope + 새 arm 없음 → 버려진다.
        assertThat(result.keptVariantLabels())
                .as("non-envelope 변형(mode=BACKORDER)은 새 arm 없으면 버려져야 한다")
                .doesNotContain("mode=BACKORDER");
    }

    /**
     * 호출자 책임: baseline(선언순 첫 상수) 제외한 enum 후보 맵 구성 후 새 generator 호출.
     * FulfillmentMode {STANDARD(baseline), EXPRESS_ONLY, BACKORDER} → non-baseline {EXPRESS_ONLY, BACKORDER}.
     */
    private static VariantPlan enumPlan(int budget) {
        Map<String, List<String>> candidates = new java.util.TreeMap<>();
        candidates.put("mode", List.of("EXPRESS_ONLY", "BACKORDER"));   // STANDARD baseline 제외
        return new ResponseFieldVariantGenerator().generate(candidates, budget);
    }

    private static boolean[] armBits(int... bits) {
        boolean[] p = new boolean[8];
        for (int b : bits) {
            p[b] = true;
        }
        return p;
    }

    private static boolean[] probesFor(ExecutionDataStore store, String className) {
        for (ExecutionData ed : store.getContents()) {
            if (ed.getName().equals(className)) {
                return ed.getProbes();
            }
        }
        return new boolean[0];
    }
}
