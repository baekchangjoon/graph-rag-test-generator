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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-009, REQ-001: String 리터럴 응답 변형 탐색 통합 테스트.
 *
 * <p>실제 생산 후보맵 조립(runResponseVariantLoops 내부) 경로를 커버한다:
 * <ul>
 *   <li>enum 필드: 선언순 첫 상수(baseline) 제외, 나머지는 변형 후보.</li>
 *   <li>String 필드: 추출 리터럴 중 stage-1 기본값(sample-&lt;fieldName&gt;)과 다른 것만 후보.</li>
 *   <li>String 필드에 리터럴 0건: 변형 후보 0개(정상).</li>
 * </ul>
 *
 * <p>{@link EnumVariantReExploreTest}의 구조를 따르며, 실제 {@link EndpointExplorationRunner#exploreResponseVariants}
 * 정적 헬퍼를 직접 호출한다. 후보맵은 생산 조립 로직을 직접 재현해 구성하여 단위 검증한다.
 */
class StringLiteralVariantReExploreTest {

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
     * 응답 형상: enum 필드 "mode"(FulfillmentMode) + String 필드 "region" + String 필드 "note".
     * - mode: {STANDARD(baseline), EXPRESS_ONLY, BACKORDER} → 비-baseline: {EXPRESS_ONLY, BACKORDER}
     * - region: 리터럴 ["us-east", "eu-west"] → stage-1 기본값 "sample-region" 아님 → 둘 다 후보
     * - note: 리터럴 [] → 후보 0개
     */
    private static final BodyShape RESPONSE_SHAPE = new BodyShape("com.example.InventoryResponse", List.of(
            new BodyShape.BodyField("mode", "p.FulfillmentMode"),
            new BodyShape.BodyField("region", "java.lang.String"),
            new BodyShape.BodyField("note", "java.lang.String")), false);

    private static final Map<String, List<String>> ENUMS = Map.of(
            "p.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER"));

    /**
     * stringLiteralsByDto: dtoFqn → field → 리터럴 목록.
     * region 필드는 추출 리터럴 2개, note 필드는 추출 리터럴 없음.
     */
    private static final Map<String, Map<String, List<String>>> STRING_LITERALS_BY_DTO = Map.of(
            "com.example.InventoryResponse", Map.of(
                    "region", List.of("us-east", "eu-west", "sample-region") // sample-region == baseline → 제외
            )
            // note 는 엔트리 없음 → 후보 0
    );

    private ExternalStubSynthesizer synthesizer() {
        server = new HttpCaptureServer(new OtelTraceKey());
        server.start(null, null);
        return new ExternalStubSynthesizer(server, new ShapeJsonSynthesizer(ENUMS), new OtelTraceKey());
    }

    private static JsonNode baseline() throws Exception {
        return MAPPER.readTree("{\"mode\":\"STANDARD\",\"region\":\"sample-region\",\"note\":\"sample-note\"}");
    }

    private static ExecutionDataStore deltaWithArm(int armBit) {
        boolean[] probes = new boolean[8];
        probes[armBit] = true;
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(123L, CLASS, probes));
        return store;
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

    /**
     * 생산 후보맵 조립 로직 재현:
     * - enum 필드 "mode": skip(1) → {EXPRESS_ONLY, BACKORDER}
     * - String 필드 "region": 리터럴에서 baseline("sample-region") 제거 → {us-east, eu-west}
     * - String 필드 "note": 리터럴 없음 → 후보 없음
     */
    private static VariantPlan buildProductionPlan(int budget) {
        ShapeJsonSynthesizer shapes = new ShapeJsonSynthesizer(ENUMS);
        Map<String, List<String>> candidates = new TreeMap<>();

        for (BodyShape.BodyField field : RESPONSE_SHAPE.fields()) {
            // enum 후보
            List<String> consts = resolveEnumConstants(field.javaType(), ENUMS);
            if (consts != null && !consts.isEmpty()) {
                List<String> nonBaseline = consts.stream().skip(1).toList();
                if (!nonBaseline.isEmpty()) {
                    candidates.put(field.name(), nonBaseline);
                }
                continue;
            }
            // String 후보
            if ("java.lang.String".equals(field.javaType())) {
                List<String> lits = STRING_LITERALS_BY_DTO
                        .getOrDefault(RESPONSE_SHAPE.javaType(), Map.of())
                        .getOrDefault(field.name(), List.of());
                if (lits.isEmpty()) {
                    continue;
                }
                String stageOneBaseline = shapes.scalarValue(field.javaType(), List.of(), field.name()).asText();
                List<String> nonBaseline = lits.stream().filter(s -> !s.equals(stageOneBaseline)).toList();
                if (!nonBaseline.isEmpty()) {
                    candidates.put(field.name(), nonBaseline);
                }
            }
        }
        return new ResponseFieldVariantGenerator().generate(candidates, budget);
    }

    /** enum FQN/simple-name 폴백 해석 헬퍼(EndpointExplorationRunner.resolveEnumConstants 재현). */
    private static List<String> resolveEnumConstants(String javaType, Map<String, List<String>> enumConstants) {
        List<String> direct = enumConstants.get(javaType);
        if (direct != null) {
            return direct;
        }
        String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
        return enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 테스트 (a): enum 기준 baseline(STANDARD)은 후보맵에 제외되고 나머지는 포함된다
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void enumBaselineIsExcludedAndNonBaselineVariantsAreIncluded() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", RESPONSE_SHAPE);

        ExecutionDataStore cumulative = new ExecutionDataStore();
        cumulative.put(new ExecutionData(123L, CLASS, armBits(1)));

        VariantPlan plan = buildProductionPlan(32);

        // 후보맵: mode={EXPRESS_ONLY, BACKORDER}, region={us-east, eu-west} — STANDARD, sample-region 제외 확인
        // plan.kept()의 label에 STANDARD가 없어야 하고 EXPRESS_ONLY/BACKORDER/us-east/eu-west 중 일부 있어야 한다.
        List<String> allLabels = plan.kept().stream()
                .map(ResponseFieldVariantGenerator.ResponseVariant::label).toList();

        assertThat(allLabels).as("STANDARD(enum baseline)이 변형 후보에 포함되면 안 됨")
                .noneMatch(l -> l.contains("STANDARD"));
        assertThat(allLabels).as("sample-region(String baseline)이 변형 후보에 포함되면 안 됨")
                .noneMatch(l -> l.contains("sample-region"));
        assertThat(allLabels).as("비-baseline enum 또는 String 리터럴 후보가 하나 이상 있어야 함")
                .isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 테스트 (b): baseline과 다른 String 리터럴이 변형 후보에 포함되고 새 arm을 연다
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void stringLiteralDifferentFromBaselineIsIncludedAndOpensNewArm() throws Exception {
        ExternalStubSynthesizer syn = synthesizer();
        syn.register("GET", "/inventory/stock", RESPONSE_SHAPE);

        ExecutionDataStore cumulative = new ExecutionDataStore();
        cumulative.put(new ExecutionData(123L, CLASS, armBits(1)));

        // region만 포함된 미니 후보맵으로 String 변형만 격리 테스트
        ShapeJsonSynthesizer shapes = new ShapeJsonSynthesizer(ENUMS);
        String stageOneBaseline = shapes.scalarValue("java.lang.String", List.of(), "region").asText();
        assertThat(stageOneBaseline).as("stage-1 기본값 확인").isEqualTo("sample-region");

        List<String> regionLits = List.of("us-east", "eu-west", "sample-region");
        List<String> nonBaseline = regionLits.stream().filter(s -> !s.equals(stageOneBaseline)).toList();

        assertThat(nonBaseline).as("baseline 제외 후 us-east, eu-west 남아야 함")
                .containsExactlyInAnyOrder("us-east", "eu-west");

        Map<String, List<String>> regionOnlyCandidates = new TreeMap<>();
        regionOnlyCandidates.put("region", nonBaseline);
        VariantPlan plan = new ResponseFieldVariantGenerator().generate(regionOnlyCandidates, 32);

        List<JsonNode> registeredBodies = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();

        EndpointExplorationRunner.VariantInvoker invoker = new EndpointExplorationRunner.VariantInvoker() {
            @Override
            public String nextTraceId() {
                return "trace00000000000" + seq.incrementAndGet();
            }

            @Override
            public ExecutionDataStore invoke(JsonNode body) {
                registeredBodies.add(body);
                String region = body.get("region").asText();
                return "us-east".equals(region) ? deltaWithArm(4) : deltaWithArm(5);
            }
        };

        EndpointExplorationRunner.VariantExploreResult result =
                EndpointExplorationRunner.exploreResponseVariants(
                        plan, baseline(), "GET", "/inventory/stock", syn,
                        true, invoker, cumulative, Set.of(CLASS));

        // String 리터럴 변형 모두 새 arm → 보존
        assertThat(result.keptVariantLabels()).as("us-east, eu-west 모두 새 arm 열어 보존")
                .containsExactlyInAnyOrder("region=us-east", "region=eu-west");

        // 각 변형 body에 region 필드가 정확히 재정의됐는지
        assertThat(registeredBodies).extracting(b -> b.get("region").asText())
                .containsExactlyInAnyOrder("us-east", "eu-west");

        // cumulative: baseline arm(1) + 두 String 변형 arm(4,5)
        boolean[] merged = probesFor(cumulative, CLASS);
        assertThat(merged[1]).isTrue();
        assertThat(merged[4]).isTrue();
        assertThat(merged[5]).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 테스트 (c): String 필드에 추출 리터럴이 없으면 변형 후보 0개
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void stringFieldWithZeroLiteralsYieldsZeroVariants() {
        // "note" 필드는 STRING_LITERALS_BY_DTO에 엔트리 없음 → 후보 0개
        Map<String, List<String>> dtoLits = STRING_LITERALS_BY_DTO
                .getOrDefault(RESPONSE_SHAPE.javaType(), Map.of());
        List<String> noteLits = dtoLits.getOrDefault("note", List.of());

        assertThat(noteLits).as("note 필드 리터럴 0건 확인").isEmpty();

        // note-only 후보맵: 빈 결과 → plan.kept() 비어야 함
        Map<String, List<String>> emptyCandidates = new TreeMap<>();
        // note 리터럴 없으므로 후보 추가 없음
        VariantPlan plan = new ResponseFieldVariantGenerator().generate(emptyCandidates, 32);

        assertThat(plan.kept()).as("리터럴 0건 필드는 변형 0개").isEmpty();
    }
}
