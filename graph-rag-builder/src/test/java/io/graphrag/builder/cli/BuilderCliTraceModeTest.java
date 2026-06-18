package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuilderCliTraceModeTest {

    @Test
    void defaultsToOtelWhenUnset() {
        assertThat(BuilderCli.traceMode(null)).isEqualTo("otel");
    }

    @Test
    void acceptsThreeModes() {
        assertThat(BuilderCli.traceMode("otel")).isEqualTo("otel");
        assertThat(BuilderCli.traceMode("sleuth")).isEqualTo("sleuth");
        assertThat(BuilderCli.traceMode("none")).isEqualTo("none");
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> BuilderCli.traceMode("log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--trace-mode");
    }

    // parseCsv는 Task 8에서 추가되는 package-private 헬퍼 — 여기서 함께 검증한다.
    @Test
    void parseCsv_handlesNullEmptySingleMultiAndWhitespace() {
        assertThat(BuilderCli.parseCsv(null)).isEmpty();
        assertThat(BuilderCli.parseCsv("")).isEmpty();
        assertThat(BuilderCli.parseCsv("   ")).isEmpty();
        assertThat(BuilderCli.parseCsv("a")).containsExactly("a");
        assertThat(BuilderCli.parseCsv("a,b,c")).containsExactly("a", "b", "c");
        assertThat(BuilderCli.parseCsv(" a , b ")).containsExactly("a", "b");
        assertThat(BuilderCli.parseCsv("a,,b")).containsExactly("a", "b");
    }
}
