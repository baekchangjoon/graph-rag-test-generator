package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.BranchRef;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.ExploredPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-006, REQ-F012-007: buildEgressAssertionPaths 순수 헬퍼 단위 테스트.
 *
 * <p>SUT/DB/invoker 없이 정적 헬퍼를 직접 호출해:
 * <ul>
 *   <li>각 KeptVariant마다 CONTRACT CapturedHttpCall이 생성되고,</li>
 *   <li>discoveredBy="egress-assertion" ExploredPath가 반환되며,</li>
 *   <li>expectedStatus가 KeptVariant의 sutStatus에서 오고,</li>
 *   <li>capturedHttpCallIds가 생성된 call의 id를 가리키는지 검증한다.</li>
 * </ul>
 */
class ResponseVariantAssertionPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode triggerInput() throws Exception {
        return MAPPER.readTree("{\"type\":\"EXPRESS\"}");
    }

    @Test
    void buildsAssertionPath_withObservedStatus_egressAssertionMarker_andContractCall()
            throws Exception {
        var branches = List.of(new BranchRef("io.x.OrderController", "checkStock", 53, 0));
        var kept = List.of(new EndpointExplorationRunner.KeptVariant(
                "region=EMBARGOED",
                MAPPER.readTree("{\"region\":\"EMBARGOED\"}"),
                422,
                branches));
        var outCalls = new ArrayList<CapturedHttpCall>();
        var paths = EndpointExplorationRunner.buildEgressAssertionPaths(
                "ep1", triggerInput(), "GET", "/inventory/stock", kept, outCalls);

        assertThat(paths).hasSize(1);
        ExploredPath path = paths.get(0);
        assertThat(path.discoveredBy()).isEqualTo("egress-assertion");
        assertThat(path.expectedStatus()).isEqualTo(422);
        assertThat(outCalls).hasSize(1);
        CapturedHttpCall call = outCalls.get(0);
        assertThat(call.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
        assertThat(call.responseBody()).contains("EMBARGOED");
        assertThat(path.capturedHttpCallIds()).containsExactly(call.id());
        assertThat(path.branchesTaken()).isEqualTo(branches);
    }

    @Test
    void callIdContainsEndpointIdAndSanitizedLabel() throws Exception {
        var kept = List.of(new EndpointExplorationRunner.KeptVariant(
                "mode=BACK ORDER",
                MAPPER.readTree("{\"mode\":\"BACK ORDER\"}"),
                200,
                List.of()));
        var outCalls = new ArrayList<CapturedHttpCall>();
        EndpointExplorationRunner.buildEgressAssertionPaths(
                "myEp", triggerInput(), "POST", "/orders", kept, outCalls);

        assertThat(outCalls).hasSize(1);
        // id should be http-<endpointId>-egressassert-<sanitizedLabel>
        assertThat(outCalls.get(0).id()).startsWith("http-myEp-egressassert-");
        // spaces -> dashes in sanitized label
        assertThat(outCalls.get(0).id()).doesNotContain(" ");
    }

    @Test
    void emptyKept_yieldsNoPaths() throws Exception {
        var outCalls = new ArrayList<CapturedHttpCall>();
        var paths = EndpointExplorationRunner.buildEgressAssertionPaths(
                "ep1", triggerInput(), "GET", "/x", List.of(), outCalls);

        assertThat(paths).isEmpty();
        assertThat(outCalls).isEmpty();
    }
}
