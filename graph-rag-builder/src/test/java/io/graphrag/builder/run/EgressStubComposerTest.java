package io.graphrag.builder.run;

import io.graphrag.builder.capture.egress.EgressCall;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EgressStubComposerTest {

    private static final ShapeJsonSynthesizer SHAPES =
            new ShapeJsonSynthesizer(java.util.Map.<String, java.util.List<String>>of());

    private static ExternalCallSite site(String method, String path, BodyShape shape) {
        return new ExternalCallSite(method, path, Optional.ofNullable(shape));
    }

    private static EgressCall call(String method, String path) {
        return new EgressCall(method, path, 200, "t", 1L);
    }

    @Test
    @DisplayName("REQ-S015-001: matched + shape → SYNTHESIZED, 비어있지 않은 body, loudFail 없음")
    void matchedSynthesizes() {
        BodyShape shape = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("type", "java.lang.String")));
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", shape)), SHAPES, Map.of());
        assertThat(o.provenance()).isEqualTo(CapturedHttpCall.Provenance.SYNTHESIZED);
        assertThat(o.responseBody()).contains("type").isNotBlank();
        assertThat(o.loudFail()).isEmpty();
    }

    @Test
    @DisplayName("REQ-S015-003: unmatched → CAPTURED, 빈 body, unmatched-external-call")
    void unmatchedLoudFails() {
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/other"),
                List.of(site("GET", "/inventory/stock",
                        new BodyShape("io.example.Resp", List.of()))), SHAPES, Map.of());
        assertThat(o.provenance()).isEqualTo(CapturedHttpCall.Provenance.CAPTURED);
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unmatched-external-call");
    }

    @Test
    @DisplayName("REQ-S015-003: matched no shape → CAPTURED, unwired-external-dep")
    void noShapeLoudFails() {
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", null)), SHAPES, Map.of());
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unwired-external-dep");
    }

    @Test
    @DisplayName("REQ-S015-003: 합성 불가 형상 → CAPTURED, unsynthesizable-shape")
    void unsynthesizableLoudFails() {
        BodyShape bad = new BodyShape("io.example.Resp",
                List.of(new BodyShape.BodyField("nested", "com.example.Nested")));
        EgressStubComposer.Outcome o = EgressStubComposer.compose(
                call("GET", "/inventory/stock"),
                List.of(site("GET", "/inventory/stock", bad)), SHAPES, Map.of());
        assertThat(o.responseBody()).isEmpty();
        assertThat(o.loudFail()).get().extracting("reason").isEqualTo("unsynthesizable-shape");
    }
}
