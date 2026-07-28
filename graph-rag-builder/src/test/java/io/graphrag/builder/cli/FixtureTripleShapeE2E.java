package io.graphrag.builder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.provenance.ProvenanceReport;
import io.graphrag.builder.provenance.ProvenanceReport.GuardFact;
import io.graphrag.builder.provenance.ProvenanceReport.Origin;
import io.graphrag.builder.provenance.ProvenanceReport.UnguardedField;
import io.graphrag.builder.provenance.ProvenanceReport.ValueRef;
import io.graphrag.builder.provenance.TripleSynthesizer;
import io.graphrag.model.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005/007/008 — <b>도구가 실제로 형상상 유효한 후보를 만드는가</b>를 fixture EP에 대해 고정하는
 * black-box E2E. SUT 부팅 없이 정적 경로만 쓴다({@code provenance} → {@code synthesize-triple},
 * {@code -Dsut.src} 재사용 — {@link ProvenanceCliE2E}와 동일 계약).
 *
 * <p>E2E-B1 수동 실증(2026-07-28, RED)이 드러낸 형상 결함의 회귀 테스트다. 당시 도구 산출물은
 * <ul>
 *   <li>{@code List<LineItem> lineItems}를 배열이 아니라 <b>객체</b>로 만들었고(400),</li>
 *   <li>가드가 읽는 INPUT 피연산자({@code invoices.total}, {@code fulfillment.parcelWeight})를
 *       body에서 <b>통째로 누락</b>했으며(400),</li>
 *   <li>{@code EXTERNAL_RESPONSE} 가드가 있는데도 {@code stubs.json}을 <b>비운 채</b> 내보냈다(500).</li>
 * </ul>
 * 아래 세 불변식({@link #assertCollectionPathsAreArrays}, {@link #assertGuardInputsPresentInBody},
 * {@link #assertExternalOperandsHaveStubSlots})은 그 셋을 각각 기계 검증한다.
 */
@EnabledIfSystemProperty(named = "sut.src", matches = ".+")
class FixtureTripleShapeE2E {

    @TempDir
    Path work;

    /**
     * {@code ProvenanceIndexer}의 {@code javaType}은 단순명이다 — body에 스칼라로 배치할 수 없는
     * 컨테이너 타입(그 자리는 원소 필드 경로가 대신 채운다).
     */
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "List", "Set", "Collection", "Iterable", "Map", "ArrayList", "HashMap", "LinkedHashMap");

    @Test
    @DisplayName("REQ-005/007/008: fixture EP 3종(invoices/fulfillment/transfers)의 후보는 형상상 유효하다 "
            + "— 컬렉션은 배열, 가드 INPUT 피연산자는 전부 body에, EXTERNAL 가드에는 stub 자리가 있다")
    void fixtureEndpointsProduceStructurallyValidCandidates() throws Exception {
        for (String endpointSpec : List.of("POST /api/invoices", "POST /api/fulfillment", "POST /api/transfers")) {
            Synthesized synthesized = runPipeline(endpointSpec);

            assertThat(synthesized.candidateDirs())
                    .as(endpointSpec + ": 후보가 최소 1개는 나와야 한다")
                    .isNotEmpty();
            for (Path candDir : synthesized.candidateDirs()) {
                JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());
                JsonNode stubs = Json.mapper().readTree(candDir.resolve("stubs.json").toFile());
                String where = endpointSpec + " / " + candDir.getFileName();

                assertCollectionPathsAreArrays(synthesized.report(), body, where);
                assertGuardInputsPresentInBody(synthesized.report(), body, where);
                assertUnguardedFieldsPresentInBody(synthesized.report(), body, where);
                assertExternalOperandsHaveStubSlots(synthesized.report(), stubs, where);
            }
        }
    }

    /**
     * {@code post-api-invoices}의 구체 형상 고정 — 위 일반 불변식이 우연히 통과하는 형태가 아니라
     * 실제 SUT DTO({@code record InvoiceRequest(int total, List<LineItem> lineItems)})와 맞는
     * 모양인지 값 수준으로 못박는다.
     */
    @Test
    @DisplayName("REQ-005: invoices 후보 body는 lineItems가 원소 1개짜리 배열이고 total이 존재한다(E2E-B1 400 회귀)")
    void invoicesBodyMatchesRequestDtoShape() throws Exception {
        Synthesized synthesized = runPipeline("POST /api/invoices");
        JsonNode body = Json.mapper().readTree(
                synthesized.candidateDirs().get(0).resolve("body.json").toFile());

        assertThat(body.get("lineItems"))
                .as("List<LineItem>은 JSON 배열이어야 한다(객체로 나오면 SUT가 400)")
                .isNotNull();
        assertThat(body.get("lineItems").isArray()).as("lineItems는 배열이어야 한다").isTrue();
        assertThat(body.get("lineItems")).hasSize(1);
        JsonNode element = body.get("lineItems").get(0);
        assertThat(element.has("sku")).as("대표원소에 sku가 있어야 한다").isTrue();
        assertThat(element.has("amount")).as("대표원소에 amount가 있어야 한다").isTrue();
        assertThat(body.has("total"))
                .as("가드 `sum != req.total()`의 INPUT 피연산자 total이 body에 있어야 한다")
                .isTrue();
        assertThat(body.get("total").asText())
                .as("total은 도구가 결정할 수 없으므로 갭 마커여야 한다(에이전트가 채울 자리)")
                .startsWith(TripleSynthesizer.GAP_MARKER_PREFIX);
    }

    /**
     * {@code post-api-fulfillment}의 stub 형상 고정 — {@code EXTERNAL_RESPONSE} 피연산자 2개
     * ({@code allowedPrefix}는 결합 논리 {@code ||} 안, {@code maxWeight}는 비교 가드)가 <b>한</b>
     * mapping에 병합돼야 한다. 실증 당시에는 {@code stubs.json}이 {@code { }}였다(500).
     */
    @Test
    @DisplayName("REQ-008: fulfillment 후보의 stubs.json은 같은 callSite의 두 응답 필드를 한 mapping에 담는다(E2E-B1 500 회귀)")
    void fulfillmentStubMergesBothExternalFieldsIntoOneMapping() throws Exception {
        Synthesized synthesized = runPipeline("POST /api/fulfillment");
        Path candDir = synthesized.candidateDirs().get(0);
        JsonNode stubs = Json.mapper().readTree(candDir.resolve("stubs.json").toFile());
        JsonNode body = Json.mapper().readTree(candDir.resolve("body.json").toFile());

        assertThat(stubs.isEmpty()).as("EXTERNAL_RESPONSE 가드가 있으면 stubs.json이 비면 안 된다").isFalse();
        assertThat(stubs.path("request").path("method").asText()).isEqualTo("GET");
        assertThat(stubs.path("request").path("urlPath").asText()).isEqualTo("/carriers/policy");
        JsonNode jsonBody = stubs.path("response").path("jsonBody");
        assertThat(jsonBody.has("allowedPrefix"))
                .as("결합 논리(||) 안의 EXTERNAL 피연산자도 stub 자리를 가져야 한다").isTrue();
        assertThat(jsonBody.has("maxWeight"))
                .as("비교 가드의 EXTERNAL 피연산자도 같은 mapping에 병합돼야 한다").isTrue();
        assertThat(stubs.path("response").path("headers").path("Content-Type").asText())
                .as("jsonBody 응답에는 Content-Type이 함께 등록돼야 한다(없으면 RestTemplate 컨버터 선택 실패 → 500)")
                .isEqualTo("application/json");
        assertThat(body.has("parcelWeight"))
                .as("가드 `req.parcelWeight() > policy.maxWeight()`의 INPUT 피연산자가 body에 있어야 한다")
                .isTrue();
        assertThat(body.get("parcelWeight").asInt())
                .as("INPUT×EXTERNAL 비교 가드는 만족 쌍을 공동 배치한다(parcelWeight <= maxWeight)")
                .isLessThanOrEqualTo(jsonBody.get("maxWeight").asInt());
    }

    /**
     * {@code post-api-quotas}는 <b>현재 합성 범위 밖</b>임을 고정한다 — {@code @RequestBody
     * Map<String, Integer>}는 키를 에이전트가 골라야 하는데, 마커 계약(REQ-009)은 base/candidate의
     * 키 집합이 같을 것을 요구하므로 "키 자리 마커"를 표현할 수 없다. 조용히 {@code {}}를 내보내지 말고
     * <b>사유를 남기는 것</b>이 계약이다(무-fabrication).
     */
    @Test
    @DisplayName("REQ-007: quotas(동적 키 Map body)는 합성 불가를 notes.md에 명시적으로 남긴다(조용한 빈 body 금지)")
    void quotasDynamicMapBodyIsLoudlyReportedAsUnsynthesizable() throws Exception {
        Synthesized synthesized = runPipeline("POST /api/quotas");
        Path candDir = synthesized.candidateDirs().get(0);

        assertThat(Json.mapper().readTree(candDir.resolve("body.json").toFile()).isEmpty())
                .as("현재 도구는 동적 키 Map body를 합성하지 못한다(알려진 갭)").isTrue();
        assertThat(Files.readString(candDir.resolve("notes.md")))
                .as("빈 body를 조용히 내보내면 안 된다 — 사유가 notes.md에 남아야 한다")
                .contains("경고(합성 불가)")
                .contains("동적 키 Map");
    }

    // ---- 불변식 ----

    /** {@code collectionPaths}의 접두 경로가 body에 있으면 그 노드는 원소 ≥1개의 JSON 배열이어야 한다. */
    private static void assertCollectionPathsAreArrays(ProvenanceReport report, JsonNode body, String where) {
        for (String path : report.collectionPaths()) {
            JsonNode node = navigate(body, path, report.collectionPaths());
            if (node == null) {
                continue;   // 이 후보가 그 컬렉션에 값을 배치하지 않았으면 검사 대상 아님
            }
            assertThat(node.isArray())
                    .as(where + ": 컬렉션 경로 '" + path + "'는 JSON 배열이어야 한다(객체면 SUT 역직렬화 400)")
                    .isTrue();
            assertThat(node).as(where + ": 컬렉션 '" + path + "'는 대표원소를 최소 1개 가져야 한다").isNotEmpty();
        }
    }

    /** 가드에 등장하는 INPUT 피연산자(컨테이너 제외)는 결정값이든 갭 마커든 body에 존재해야 한다. */
    private static void assertGuardInputsPresentInBody(ProvenanceReport report, JsonNode body, String where) {
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() != Origin.INPUT || v.jsonPath() == null || v.jsonPath().isBlank()) {
                    continue;
                }
                if (CONTAINER_TYPES.contains(v.javaType()) || report.collectionPaths().contains(v.jsonPath())) {
                    continue;   // 컨테이너 자신은 스칼라 자리가 아니다 — 원소 필드가 대신 배치된다
                }
                assertThat(navigate(body, v.jsonPath(), report.collectionPaths()))
                        .as(where + ": 가드(" + guard.op() + " at " + guard.at() + ")가 읽는 INPUT '"
                                + v.jsonPath() + "'가 body에 없다 — 이 필드 없이는 happy path가 성립하지 않는다")
                        .isNotNull();
            }
        }
    }

    /** unguarded 필드는 전부 채움 슬롯이므로 body에 자리가 있어야 한다(REQ-007). */
    private static void assertUnguardedFieldsPresentInBody(ProvenanceReport report, JsonNode body, String where) {
        for (UnguardedField field : report.unguarded()) {
            assertThat(navigate(body, field.jsonPath(), report.collectionPaths()))
                    .as(where + ": unguarded 필드 '" + field.jsonPath() + "'가 body에 배치되지 않았다")
                    .isNotNull();
        }
    }

    /** callSite가 HTTP 형식인 EXTERNAL_RESPONSE 피연산자는 stub의 response.jsonBody에 자리를 가져야 한다. */
    private static void assertExternalOperandsHaveStubSlots(ProvenanceReport report, JsonNode stubs, String where) {
        Set<String> expected = new LinkedHashSet<>();
        for (GuardFact guard : report.guards()) {
            for (ValueRef v : guard.operands()) {
                if (v.origin() == Origin.EXTERNAL_RESPONSE && v.stubField() != null
                        && v.callSite() != null && isHttpCallSite(v.callSite())) {
                    expected.add(v.stubField());
                }
            }
        }
        if (expected.isEmpty()) {
            return;
        }
        JsonNode jsonBody = stubs.path("response").path("jsonBody");
        for (String field : expected) {
            assertThat(jsonBody.has(field))
                    .as(where + ": EXTERNAL_RESPONSE 피연산자 '" + field + "'의 stub 자리가 없다 — "
                            + "stub 없이 실제 외부 호출이 나가 5xx가 된다")
                    .isTrue();
        }
    }

    private static boolean isHttpCallSite(String callSite) {
        int sp = callSite.indexOf(' ');
        return sp > 0 && callSite.substring(sp + 1).startsWith("/")
                && Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                        .contains(callSite.substring(0, sp));
    }

    /** dot-path를 배열 접두사(대표원소 index 0)를 따라 내려가며 조회한다. 없으면 null. */
    private static JsonNode navigate(JsonNode root, String jsonPath, List<String> collectionPaths) {
        JsonNode node = root;
        StringBuilder prefix = new StringBuilder();
        String[] segments = jsonPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(segments[i]);
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segments[i]);
            if (node != null && node.isArray() && i < segments.length - 1
                    && collectionPaths.contains(prefix.toString())) {
                node = node.isEmpty() ? null : node.get(0);
            }
        }
        return node;
    }

    // ---- 파이프라인 구동 ----

    private record Synthesized(ProvenanceReport report, List<Path> candidateDirs) {
    }

    /** {@code provenance} → {@code synthesize-triple}을 문서화된 순서 그대로 in-process로 돌린다. */
    private Synthesized runPipeline(String endpointSpec) throws Exception {
        String endpointId = endpointSpec.toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '-').replace('/', '-');
        Path reportFile = work.resolve(endpointId + "-provenance.json");
        Path tripleStore = work.resolve(endpointId + "-triples");

        BuilderCli.main(new String[] {
                "provenance",
                "--sut-src", System.getProperty("sut.src"),
                "--endpoint", endpointSpec,
                "--out", reportFile.toString()
        });
        BuilderCli.main(new String[] {
                "synthesize-triple",
                "--report", reportFile.toString(),
                "--triple-store", tripleStore.toString()
        });

        ProvenanceReport report = Json.mapper().readValue(reportFile.toFile(), ProvenanceReport.class);
        Path endpointDir = tripleStore.resolve(report.endpointId());
        try (var entries = Files.list(endpointDir)) {
            List<Path> candidateDirs = entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("cand-"))
                    .sorted()
                    .toList();
            return new Synthesized(report, candidateDirs);
        }
    }
}
