package io.graphrag.generator.compose;

import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-S015-006: 비어있지 않은 형상 body를 가진 egress CapturedHttpCall → 비어있지 않은 stub 방출. */
class HttpMockComposerEgressTest {

    @Test
    @DisplayName("REQ-S015-006: egress CapturedHttpCall → respondJson(비어있지 않은 body) 방출")
    void emitsNonEmptyStubBody() {
        CapturedHttpCall call = new CapturedHttpCall(
                "http-p1-egress-1", "p1", "GET", "/inventory/stock",
                Map.of(), null, 200, "{\"type\":\"sample\"}",
                List.of("type"), false, CapturedHttpCall.Provenance.SYNTHESIZED);

        HttpMockComposer.ComposedMocks mocks = new HttpMockComposer().compose(List.of(call));

        assertThat(mocks.block()).contains("scope.http().stub(\"GET\", \"/inventory/stock\")");
        assertThat(mocks.block()).contains(".respondJson(200,");
        assertThat(mocks.block()).contains("type");
        assertThat(mocks.block()).doesNotContain(".respondJson(200, \"\")");
    }
}
