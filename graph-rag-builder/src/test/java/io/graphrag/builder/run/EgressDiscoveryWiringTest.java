package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.IntNode;
import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.explore.PathCandidate;
import io.graphrag.builder.explore.RawHttpExchange;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-004/005: captureHttpCalls가 egress를 병합·dedup하는지 검증.
 * captureHttpCalls는 private이라 reflection으로 호출(ExternalStubLoudFailTest 패턴 준용).
 */
class EgressDiscoveryWiringTest {

    /** httpCapture + egressCollector(null) 배선한 최소 runner(나머지 의존성 null/empty). */
    private static EndpointExplorationRunner runnerWithoutCollector() {
        return new EndpointExplorationRunner(
                /* sut */ null, /* connection */ null, /* dbType */ null,
                /* coverage */ null, /* analyzer */ null, /* budgetRequests */ 0,
                /* httpCapture */ null,
                /* responseDtoFieldSets */ List.of(),
                /* literalCandidates */ List.of(),
                /* authProvider */ null, /* authConfig */ null,
                /* enumConstants */ Map.of(), /* enumColumns */ Map.of(),
                /* extraHeaders */ null, /* sqlCapture */ null, /* kafkaCapture */ null,
                /* classifier */ null,
                /* callSites */ List.of(),
                /* egressCollector */ null);
    }

    /** redirect exchange 1건 + egressCalls 2건(dup 포함)으로 PathCandidate 구성. */
    private static PathCandidate candidateWithRedirectAndEgress(
            String redirectMethod, String redirectPath,
            List<EgressCall> egressCalls) {
        RawHttpExchange redirect = new RawHttpExchange(
                redirectMethod, redirectPath, Map.of(), null, 201, "", false, "");
        return new PathCandidate(
                "p1",
                IntNode.valueOf(0),
                201,
                IntNode.valueOf(0),
                List.of(),
                "heuristic",
                0, 0,
                List.of(redirect),
                List.of(),
                List.of(),
                null,
                Map.of(),
                egressCalls);
    }

    @SuppressWarnings("unchecked")
    private static List<CapturedHttpCall> invokeCaptureHttpCalls(
            EndpointExplorationRunner runner, PathCandidate candidate) throws Exception {
        Method m = EndpointExplorationRunner.class
                .getDeclaredMethod("captureHttpCalls", PathCandidate.class);
        m.setAccessible(true);
        return (List<CapturedHttpCall>) m.invoke(runner, candidate);
    }

    @Test
    @DisplayName("REQ-005: captureHttpCalls merges egress (dedup vs redirect)")
    void mergesEgress() throws Exception {
        // redirect: POST /reservations
        // egress:   POST /reservations (중복) + GET /x
        List<EgressCall> egress = List.of(
                new EgressCall("POST", "/reservations", null, "t", 1L),
                new EgressCall("GET", "/x", 200, "t", 2L));
        PathCandidate candidate = candidateWithRedirectAndEgress("POST", "/reservations", egress);

        EndpointExplorationRunner runner = runnerWithoutCollector();
        List<CapturedHttpCall> calls = invokeCaptureHttpCalls(runner, candidate);

        // redirect가 POST /reservations 1건 + egress GET /x 1건 = 총 2건, POST /reservations 중복 제거
        assertThat(calls).extracting(c -> c.method() + " " + c.urlPath())
                .containsExactlyInAnyOrder("POST /reservations", "GET /x");
    }

    @Test
    @DisplayName("REQ-004: egressCalls empty → redirect only")
    void noEgressReturnsRedirectOnly() throws Exception {
        PathCandidate candidate = candidateWithRedirectAndEgress("GET", "/items", List.of());

        EndpointExplorationRunner runner = runnerWithoutCollector();
        List<CapturedHttpCall> calls = invokeCaptureHttpCalls(runner, candidate);

        assertThat(calls).extracting(c -> c.method() + " " + c.urlPath())
                .containsExactly("GET /items");
    }
}
