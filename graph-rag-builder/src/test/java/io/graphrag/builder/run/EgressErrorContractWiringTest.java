package io.graphrag.builder.run;

import io.graphrag.builder.oracle.ClassifierConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EgressErrorContractWiringTest {

    @Test
    void nullDescriptor_whenErrorWhenPresentEmpty() {
        var cfg = ClassifierConfig.from(Map.of());   // errorWhenPresent 비어있음, semanticStatusField 기본 errorCode
        assertThat(ErrorContractDescriptor.fromClassifierConfig(cfg)).isNull();
    }

    @Test
    void nonNullDescriptor_whenErrorWhenPresentSet() {
        var cfg = ClassifierConfig.from(Map.of(
            "--error-when-present", "errorCode",
            "--error-detail-field", "errorDetail",
            "--error-detail-contains", "BizException"));
        var d = ErrorContractDescriptor.fromClassifierConfig(cfg);
        assertThat(d).isNotNull();
        assertThat(d.errorDetailContains()).isEqualTo("BizException");
    }
}
