package io.graphrag.testlib.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdaptersTest {

    @Test
    void select_picksAdapterByEnvVariable() {
        Env env = Env.of(Map.of("JDBC_ADAPTER", "plain"));
        JdbcAdapter adapter = Adapters.select(JdbcAdapter.class, "JDBC_ADAPTER", "plain", env);
        assertThat(adapter.name()).isEqualTo("plain");
    }

    @Test
    void select_usesDefaultWhenEnvMissing() {
        Env env = Env.of(Map.of());
        HttpMockAdapter adapter = Adapters.select(HttpMockAdapter.class, "HTTP_MOCK_ADAPTER", "noop", env);
        assertThat(adapter.name()).isEqualTo("noop");
    }

    @Test
    void select_failsFastWhenNoAdapterMatches() {
        Env env = Env.of(Map.of("JDBC_ADAPTER", "does-not-exist"));
        assertThatThrownBy(() -> Adapters.select(JdbcAdapter.class, "JDBC_ADAPTER", "plain", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }
}
