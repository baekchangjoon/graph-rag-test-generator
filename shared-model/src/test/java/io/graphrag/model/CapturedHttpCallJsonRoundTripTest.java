package io.graphrag.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-011/012: responseProvenance 추가 + 레거시 JSON·10-arg 생성자 하위호환. */
class CapturedHttpCallJsonRoundTripTest {

    @Test
    void legacyJsonDefaultsToCaptured() throws Exception {
        String legacy = "{\"id\":\"h1\",\"pathId\":\"p1\",\"method\":\"GET\",\"urlPath\":\"/x\",\"responseStatus\":200}";
        CapturedHttpCall c = Json.mapper().readValue(legacy, CapturedHttpCall.class);
        assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
    }

    @Test
    void compatConstructorDefaultsCaptured() {
        CapturedHttpCall c = new CapturedHttpCall(
                "h", "p", "GET", "/x", Map.of(), null, 200, "{}", List.of(), false);
        assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
    }

    @Test
    void synthesizedProvenanceRoundTrips() throws Exception {
        CapturedHttpCall c = new CapturedHttpCall(
                "h", "p", "GET", "/x", Map.of(), null, 200, "{}", List.of(), false,
                CapturedHttpCall.Provenance.SYNTHESIZED);
        CapturedHttpCall back = Json.mapper().readValue(
                Json.mapper().writeValueAsString(c), CapturedHttpCall.class);
        assertThat(back.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
    }

    @Test
    void nullProvenanceDefaultsCaptured() {
        CapturedHttpCall c = new CapturedHttpCall(
                "h", "p", "GET", "/x", Map.of(), null, 200, "{}", List.of(), false, null);
        assertThat(c.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
    }
}
