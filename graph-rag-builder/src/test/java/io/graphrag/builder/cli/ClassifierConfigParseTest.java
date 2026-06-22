package io.graphrag.builder.cli;

import io.graphrag.builder.oracle.ClassifierConfig;
import io.graphrag.builder.oracle.ErrorEnvelopeClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierConfigParseTest {

    @Test
    void noFlagsYieldStatusOnly() {
        ClassifierConfig cfg = ClassifierConfig.from(Map.of());
        assertThat(cfg.toClassifier()).isInstanceOf(StatusOnlyClassifier.class);
    }

    @Test
    void errorWhenPresentYieldsEnvelopeClassifier() {
        ClassifierConfig cfg = ClassifierConfig.from(Map.of("--error-when-present", "errorCode"));
        assertThat(cfg.toClassifier()).isInstanceOf(ErrorEnvelopeClassifier.class);
        assertThat(cfg.semanticStatusField()).isEqualTo("errorCode");  // 기본값
    }

    @Test
    void errorWhenPresent_spacesAroundTokens_trimmed() {
        ClassifierConfig cfg = ClassifierConfig.from(Map.of("--error-when-present", "errorCode, foo"));
        assertThat(cfg.errorWhenPresent()).containsExactly("errorCode", "foo");
    }
}
