package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.IntNode;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.explore.RawHttpExchange;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * provenance 태깅(REQ-011) + loud-fail(REQ-010) 검증.
 * captureHttpCalls는 private이라 reflection으로 호출(OtelHttpCaptureIntegrationTest의 doSend 패턴과 동일).
 */
class ExternalStubLoudFailTest {

    private HttpCaptureServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static final BodyShape INV_SHAPE = new BodyShape("InventoryResponse",
            List.of(new BodyShape.BodyField("available", "Integer")), false);

    /** httpCapture + callSites만 배선한 최소 runner(나머지 의존성 null/empty — captureHttpCalls는 안 씀). */
    private EndpointExplorationRunner runnerWith(HttpCaptureServer cap, List<ExternalCallSite> sites) {
        return new EndpointExplorationRunner(
                /* sut */ null, /* connection */ null, /* dbType */ null,
                /* coverage */ null, /* analyzer */ null, /* budgetRequests */ 0,
                cap, /* responseDtoFieldSets */ List.of(), /* literalCandidates */ List.of(),
                /* authProvider */ null, /* authConfig */ null,
                /* enumConstants */ Map.of(), /* enumColumns */ Map.of(),
                /* extraHeaders */ null, /* sqlCapture */ null, /* kafkaCapture */ null,
                /* classifier */ null, sites);
    }

    @SuppressWarnings("unchecked")
    private List<CapturedHttpCall> captureHttpCalls(EndpointExplorationRunner runner, PathCandidate pc)
            throws Exception {
        Method m = EndpointExplorationRunner.class.getDeclaredMethod("captureHttpCalls", PathCandidate.class);
        m.setAccessible(true);
        return (List<CapturedHttpCall>) m.invoke(runner, pc);
    }

    private static PathCandidate candidateWith(RawHttpExchange ex) {
        return new PathCandidate("p1", IntNode.valueOf(0), 200, IntNode.valueOf(0),
                List.of(), "heuristic", 0, 0, List.of(ex));
    }

    @Test
    void synthesizedStubCallIsTaggedSynthesized() throws Exception {
        server = new HttpCaptureServer();
        server.start(null, null);
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(INV_SHAPE)));
        EndpointExplorationRunner runner = runnerWith(server, sites);

        // 합성 stub을 runner 내부 stubSynthesizer로 등록(B2 루프가 하는 일). provenance 판정이 그 인스턴스를 본다.
        EndpointExplorationRunner.StubSynthesisResult r = registerViaRunner(runner, sites);
        assertThat(r.newlyRegistered()).isEqualTo(1);

        RawHttpExchange synthCall = new RawHttpExchange("GET", "/inventory/stock", Map.of(),
                null, 200, "{\"available\":1}", false, "");
        List<CapturedHttpCall> calls = captureHttpCalls(runner, candidateWith(synthCall));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).responseProvenance())
                .isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
    }

    @Test
    void nonSynthesizedCallIsTaggedCaptured() throws Exception {
        server = new HttpCaptureServer();
        server.start(null, null);
        // callSites 비어 있음 → 어떤 호출도 합성 stub과 매칭되지 않음 → CAPTURED.
        EndpointExplorationRunner runner = runnerWith(server, List.of());

        RawHttpExchange realCall = new RawHttpExchange("GET", "/external/thing", Map.of(),
                null, 200, "{\"x\":1}", false, "");
        List<CapturedHttpCall> calls = captureHttpCalls(runner, candidateWith(realCall));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).responseProvenance())
                .isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
    }

    /**
     * REQ-010 stub-ineffective: stub 등록 후에도 재invoke에서 여전히 404로 남는 외부 호출은
     * stub-ineffective loud-fail로 기록된다(silent 금지).
     */
    @Test
    void stubRegisteredButStill404RecordsStubIneffective() throws Exception {
        server = new HttpCaptureServer();
        server.start(null, null);
        List<ExternalCallSite> sites = List.of(
                new ExternalCallSite("GET", "/inventory/stock", Optional.of(INV_SHAPE)));
        EndpointExplorationRunner runner = runnerWith(server, sites);

        // 1) stub 등록(B2 루프가 하는 일).
        assertThat(registerViaRunner(runner, sites).newlyRegistered()).isEqualTo(1);

        // 2) 등록됐는데도 재invoke에서 같은 path가 여전히 404 → stub-ineffective.
        RawHttpExchange still404 = new RawHttpExchange("GET", "/inventory/stock", Map.of(),
                null, 404, "", false, "");
        var outcome = new io.graphrag.builder.explore.ExplorationOutcome(
                List.of(candidateWith(still404)), java.util.Set.of(), Map.of());
        recordIneffectiveStubs(runner, outcome);

        assertThat(externalLoudFails(runner))
                .anyMatch(lf -> lf.reason().equals("stub-ineffective")
                        && lf.target().contains("/inventory/stock"));
    }

    private void recordIneffectiveStubs(EndpointExplorationRunner runner,
                                        io.graphrag.builder.explore.ExplorationOutcome outcome) throws Exception {
        Method m = EndpointExplorationRunner.class.getDeclaredMethod(
                "recordIneffectiveStubs", io.graphrag.model.Endpoint.class,
                io.graphrag.builder.explore.ExplorationOutcome.class);
        m.setAccessible(true);
        m.invoke(runner, null, outcome);
    }

    @SuppressWarnings("unchecked")
    private List<EndpointExplorationRunner.LoudFail> externalLoudFails(EndpointExplorationRunner runner)
            throws Exception {
        java.lang.reflect.Field f = EndpointExplorationRunner.class.getDeclaredField("externalLoudFails");
        f.setAccessible(true);
        return (List<EndpointExplorationRunner.LoudFail>) f.get(runner);
    }

    /** runner 내부 stubSynthesizer로 등록(provenance 판정이 그 인스턴스를 본다). */
    private EndpointExplorationRunner.StubSynthesisResult registerViaRunner(
            EndpointExplorationRunner runner, List<ExternalCallSite> sites) throws Exception {
        java.lang.reflect.Field f = EndpointExplorationRunner.class.getDeclaredField("stubSynthesizer");
        f.setAccessible(true);
        ExternalStubSynthesizer syn = (ExternalStubSynthesizer) f.get(runner);
        return EndpointExplorationRunner.synthesizeStubsForUnmatched(
                List.of(new RawHttpExchange("GET", "/inventory/stock", Map.of(), null, 404, "", false, "")),
                sites, syn);
    }
}
