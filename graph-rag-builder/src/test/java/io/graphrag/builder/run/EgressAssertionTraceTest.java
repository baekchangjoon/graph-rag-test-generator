package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.ExploredPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-013: egress-assertion ExploredPath는 의도적으로 빈 coverageTraceIds를 가진다.
 *
 * <p>buildEgressAssertionPaths 헬퍼를 직접 호출해 생성된 path의 coverageTraceIds가
 * 비어 있는지 검증한다(egress-assertion은 별도 probe 없음).
 */
class EgressAssertionTraceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void egressAssertionPathHasEmptyTraceIds() throws Exception {
        JsonNode triggerInput = MAPPER.readTree("{\"type\":\"EXPRESS\"}");
        EndpointExplorationRunner.KeptVariant kv = new EndpointExplorationRunner.KeptVariant(
                "status=ACCEPTED",
                MAPPER.readTree("{\"status\":\"ACCEPTED\"}"),
                200,
                List.of());
        List<io.graphrag.model.CapturedHttpCall> outCalls = new ArrayList<>();
        List<ExploredPath> paths = EndpointExplorationRunner.buildEgressAssertionPaths(
                "ep1", triggerInput, "POST", "/orders", List.of(kv), outCalls);

        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).coverageTraceIds()).isEmpty();
    }
}
