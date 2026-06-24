package io.graphrag.builder.run;

import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-001/002/010(silent-fallback)/011: EgressStubComposer happy body String 리터럴·CONTRACT
 */
class EgressStubComposerContractTest {

    private EgressCall egressCall;
    private List<ExternalCallSite> callSites;
    private ShapeJsonSynthesizer shapes;
    private Map<String, Map<String, List<String>>> stringLiterals;

    @BeforeEach
    void setUp() {
        egressCall = new EgressCall("GET", "/inventory/stock", 200, "t", 1L);

        BodyShape shape = new BodyShape(
                "io.x.InventoryResponse",
                List.of(
                        new BodyShape.BodyField("region", "java.lang.String"),
                        new BodyShape.BodyField("mode", "io.x.FulfillmentMode")
                )
        );
        callSites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(shape))
        );

        shapes = new ShapeJsonSynthesizer(
                Map.of("io.x.FulfillmentMode", List.of("STANDARD", "EXPRESS_ONLY", "BACKORDER"))
        );

        stringLiterals = Map.of(
                "io.x.InventoryResponse", Map.of("region", List.of("EMBARGOED"))
        );
    }

    @Test
    @DisplayName("REQ-F012-001/011: String 리터럴 덮어쓰기 → region=EMBARGOED, provenance=CONTRACT")
    void literalSeeding_setsRegionToExtractedLiteral_andContractProvenance() throws Exception {
        var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
        var body = Json.mapper().readTree(outcome.responseBody());
        assertThat(body.get("region").asText()).isEqualTo("EMBARGOED");
        assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
    }

    @Test
    @DisplayName("REQ-F012-002: 리터럴 없음 → enum 첫 상수(STANDARD), provenance=SYNTHESIZED")
    void enumOnlyStaysSynthesized_whenNoLiteral() throws Exception {
        var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of());
        var body = Json.mapper().readTree(outcome.responseBody());
        assertThat(body.get("mode").asText()).isEqualTo("STANDARD");
        assertThat(outcome.provenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
    }

    @Test
    @DisplayName("REQ-F012-010: 리터럴 없음(폴백) → loudFail 없음(silent)")
    void fallbackSilent_noLoudFail_whenNoLiteral() {
        var outcome = EgressStubComposer.compose(egressCall, callSites, shapes, Map.of());
        assertThat(outcome.loudFail()).isEmpty();
    }

    @Test
    @DisplayName("REQ-F012-011: 동일 입력 → 동일 출력(결정성)")
    void deterministic_sameInputSameOutput() {
        var a = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
        var b = EgressStubComposer.compose(egressCall, callSites, shapes, stringLiterals);
        assertThat(a.responseBody()).isEqualTo(b.responseBody());
    }
}
