package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.IntNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.explore.RawHttpExchange;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-S015-001/002/004/005: captureHttpCalls의 egress enrichment(형상-시드 stub) 검증. */
class CaptureHttpCallsEgressEnrichTest {

    private static ExternalCallSite siteWithStringField(String method, String path, String field) {
        BodyShape shape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField(field, "java.lang.String")));
        return new ExternalCallSite(method, path, Optional.of(shape));
    }

    private static EndpointExplorationRunner runner(
            List<ExternalCallSite> callSites, List<Set<String>> responseDtoFieldSets) {
        return new EndpointExplorationRunner(
                null, null, null, null, null, 0,
                /* httpCapture */ null,
                /* responseDtoFieldSets */ responseDtoFieldSets,
                /* literalCandidates */ List.of(),
                null, null,
                /* enumConstants */ Map.of(), /* enumColumns */ Map.of(),
                null, null, null, null,
                /* callSites */ callSites,
                /* egressCollector */ null);
    }

    private static PathCandidate candidate(List<EgressCall> egress) {
        return new PathCandidate("p1", IntNode.valueOf(0), 200, IntNode.valueOf(0),
                List.of(), "heuristic", 0, 0,
                /* httpExchanges */ List.of(),
                List.of(), List.of(), null, Map.of(),
                egress);
    }

    @SuppressWarnings("unchecked")
    private static List<CapturedHttpCall> capture(EndpointExplorationRunner runner, PathCandidate pc)
            throws Exception {
        Method m = EndpointExplorationRunner.class.getDeclaredMethod("captureHttpCalls", PathCandidate.class);
        m.setAccessible(true);
        return (List<CapturedHttpCall>) m.invoke(runner, pc);
    }

    @SuppressWarnings("unchecked")
    private static List<?> loudFails(EndpointExplorationRunner runner) throws Exception {
        java.lang.reflect.Field f = EndpointExplorationRunner.class.getDeclaredField("externalLoudFails");
        f.setAccessible(true);
        return (List<?>) f.get(runner);
    }

    @Test
    @DisplayName("REQ-S015-001: matched egress → SYNTHESIZED + 비어있지 않은 형상 body")
    void matchedEgressSynthesizesBody() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")),
                List.of(Set.of("type")));
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/inventory/stock", 200, "t", 1L))));

        assertThat(out).hasSize(1);
        CapturedHttpCall c = out.get(0);
        assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(c.responseBody()).isNotBlank();
        assertThat(c.responseBody()).contains("type");
        assertThat(c.consumedFields()).contains("type");          // REQ-S015-002
        assertThat(c.method()).isEqualTo("GET");
        assertThat(c.urlPath()).isEqualTo("/inventory/stock");
    }

    @Test
    @DisplayName("REQ-S015-004: callSites 빈 → 기존 빈-body CAPTURED, loud-fail 없음")
    void emptyCallSitesKeepsLegacy() throws Exception {
        EndpointExplorationRunner runner = runner(List.of(), List.of());
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/anything", 200, "t", 1L))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(out.get(0).responseBody()).isEmpty();
        assertThat(loudFails(runner)).isEmpty();
    }

    @Test
    @DisplayName("REQ-S015-005: 2-pass 반복 호출에도 loud-fail 중복 누적 없음")
    void noDuplicateLoudFailsAcrossPasses() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")), List.of());
        PathCandidate pc = candidate(List.of(new EgressCall("GET", "/unmatched", 200, "t", 1L)));
        capture(runner, pc);
        capture(runner, pc);
        assertThat(loudFails(runner)).hasSize(1);
    }

    @Test
    @DisplayName("REQ-S015-002: collection(array) 형상 → consumedFields 빈, body는 비어있지 않은 array")
    void collectionShapeYieldsArrayBodyEmptyConsumed() throws Exception {
        BodyShape arrayShape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("type", "java.lang.String")), true);
        EndpointExplorationRunner runner = runner(
                List.of(new ExternalCallSite("GET", "/inventory/list", Optional.of(arrayShape))),
                List.of(Set.of("type")));
        List<CapturedHttpCall> out = capture(runner,
                candidate(List.of(new EgressCall("GET", "/inventory/list", 200, "t", 1L))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(out.get(0).responseBody()).startsWith("[").isNotBlank();   // array JSON
        assertThat(out.get(0).consumedFields()).isEmpty();                    // array root → 투영 비활성
    }

    @Test
    @DisplayName("REQ-S015-005: redirect-exchange가 enriched egress보다 우선(dedup)")
    void redirectWinsOverEnrichedEgress() throws Exception {
        EndpointExplorationRunner runner = runner(
                List.of(siteWithStringField("GET", "/inventory/stock", "type")), List.of(Set.of("type")));
        // redirect exchange(CAPTURED) + 동일 (GET,/inventory/stock) egress
        RawHttpExchange redirect = new RawHttpExchange(
                "GET", "/inventory/stock", Map.of(), null, 200, "{\"redirected\":true}", false, "");
        PathCandidate pc = new PathCandidate("p1", IntNode.valueOf(0), 200, IntNode.valueOf(0),
                List.of(), "heuristic", 0, 0,
                List.of(redirect), List.of(), List.of(), null, Map.of(),
                List.of(new EgressCall("GET", "/inventory/stock", 200, "t", 1L)));
        List<CapturedHttpCall> out = capture(runner, pc);
        // 같은 (method,urlPath) 1건만 — redirect(existing) 우선
        assertThat(out).filteredOn(c -> c.urlPath().equals("/inventory/stock")).hasSize(1);
        assertThat(out.get(0).responseBody()).contains("redirected");
    }
}
