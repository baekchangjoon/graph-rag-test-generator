package io.graphrag.builder.run;

import io.graphrag.model.CapturedHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-F012-005: buildEnvelopeVariantCall 순수 헬퍼 단위 테스트.
 * errorContract null → Optional.empty / non-null → CONTRACT CapturedHttpCall 생성.
 */
class EnvelopeVariantCallTest {

    private final ErrorEnvelopeSynthesizer synth = new ErrorEnvelopeSynthesizer();

    @Test
    @DisplayName("REQ-F012-005(a): errorContract null → Optional.empty")
    void nullDescriptor_returnsEmpty() {
        Optional<CapturedHttpCall> result = EndpointExplorationRunner.buildEnvelopeVariantCall(
                "ep1", "GET", "/api/items", null, synth);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("REQ-F012-005(b): non-null descriptor → CONTRACT call, body contains ERROR and BizException")
    void nonNullDescriptor_returnsContractCall() {
        var descriptor = new ErrorContractDescriptor(
                List.of("errorCode"), "errorCode", "errorDetail", "BizException");

        Optional<CapturedHttpCall> result = EndpointExplorationRunner.buildEnvelopeVariantCall(
                "ep1", "GET", "/api/items", descriptor, synth);

        assertThat(result).isPresent();
        CapturedHttpCall call = result.get();
        assertThat(call.responseProvenance()).isEqualTo(CapturedHttpCall.Provenance.CONTRACT);
        assertThat(call.responseStatus()).isEqualTo(200);
        assertThat(call.responseBody()).contains("ERROR");
        assertThat(call.responseBody()).contains("BizException");
        assertThat(call.id()).startsWith("http-ep1-egressenvelope-");
        assertThat(call.consumedFields()).isEmpty();
    }
}
