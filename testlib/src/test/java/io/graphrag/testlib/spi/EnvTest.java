package io.graphrag.testlib.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvTest {

    @Test
    void get_returnsValueOrNull() {
        Env env = Env.of(Map.of("APP_BASE_URI", "http://localhost:8080"));
        assertThat(env.get("APP_BASE_URI")).isEqualTo("http://localhost:8080");
        assertThat(env.get("MISSING")).isNull();
    }

    @Test
    void require_failsFastWithVariableName() {
        Env env = Env.of(Map.of());
        assertThatThrownBy(() -> env.require("JDBC_URL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_URL");
    }

    @Test
    void getOrDefault_usesDefaultWhenMissing() {
        Env env = Env.of(Map.of());
        assertThat(env.getOrDefault("HTTP_MOCK_ADAPTER", "noop")).isEqualTo("noop");
    }
}
