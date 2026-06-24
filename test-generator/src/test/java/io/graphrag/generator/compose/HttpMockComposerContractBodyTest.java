package io.graphrag.generator.compose;

import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-009: CONTRACT provenance CapturedHttpCall의 responseBody가
 * stubBody에 그대로 방출되는지 검증 (회귀 가드).
 * consumedFields가 비어 있으면 retain 투영이 일어나지 않아 전체 body가 방출된다.
 */
class HttpMockComposerContractBodyTest {

    @Test
    @DisplayName("REQ-F012-009: CONTRACT body가 플레이스홀더 없이 verbatim 방출")
    void emitsContractBodyVerbatim_notPlaceholder() {
        var call = new CapturedHttpCall("h1", "p1", "GET", "/inventory/stock", Map.of(), null,
                200, "{\"region\":\"EMBARGOED\",\"mode\":\"BACKORDER\"}", List.of(), false,
                CapturedHttpCall.Provenance.CONTRACT);
        var mocks = new HttpMockComposer().compose(List.of(call));
        assertThat(mocks.block()).contains("EMBARGOED");
        assertThat(mocks.block()).doesNotContain("sample-region");
    }
}
