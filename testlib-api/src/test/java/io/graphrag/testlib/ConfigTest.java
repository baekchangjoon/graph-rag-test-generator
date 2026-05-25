package io.graphrag.testlib;

import io.graphrag.testlib.scope.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    @Test
    void readsValuesFromExplicitMap() {
        Config c = Config.from(Map.of(
                "APP_BASE_URI", "http://app:8080",
                "JDBC_URL", "jdbc:postgresql://postgres:5432/app",
                "JDBC_USER", "app",
                "JDBC_PASS", "app",
                "HTTP_MOCK_ADAPTER", "wiremock",
                "DASHBOARD_URL", "http://dashboard:8080"));

        assertThat(c.appBaseUri()).isEqualTo("http://app:8080");
        assertThat(c.jdbcUrl()).isEqualTo("jdbc:postgresql://postgres:5432/app");
        assertThat(c.httpMockAdapterName()).isEqualTo("wiremock");
        assertThat(c.dashboardUrl()).isEqualTo("http://dashboard:8080");
    }

    @Test
    void absentDashboardUrlIsNull() {
        Config c = Config.from(Map.of("APP_BASE_URI", "http://app"));
        assertThat(c.dashboardUrl()).isNull();
    }

    @Test
    void defaultAdapterNamesWhenAbsent() {
        Config c = Config.from(Map.of());

        assertThat(c.httpMockAdapterName()).isEqualTo("noop");
        assertThat(c.socketMockAdapterName()).isEqualTo("noop");
        assertThat(c.authAdapterName()).isEqualTo("noop");
        assertThat(c.dashboardAdapterName()).isEqualTo("noop");
    }

    @Test
    void authModeDefaultsToDisabled() {
        Config c = Config.from(Map.of());
        assertThat(c.authMode()).isEqualTo("disabled");
    }

    @Test
    void authModeReadsExplicitValue() {
        Config c = Config.from(Map.of("TEST_AUTH_MODE", "real"));
        assertThat(c.authMode()).isEqualTo("real");
    }

    @Test
    void baggageKeyDefaultsToTestId() {
        Config c = Config.from(Map.of());
        assertThat(c.baggageKey()).isEqualTo("test-id");
    }

    @Test
    void getRawReturnsAnyKey() {
        Config c = Config.from(Map.of("CUSTOM_KEY", "value"));
        assertThat(c.getRaw("CUSTOM_KEY")).isEqualTo("value");
        assertThat(c.getRaw("MISSING")).isNull();
    }
}
