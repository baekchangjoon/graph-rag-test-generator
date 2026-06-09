package io.graphrag.testlib.api;

import io.graphrag.testlib.spi.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestScopeTest {

    private static final Map<String, String> VALID_ENV = Map.of(
            "APP_BASE_URI", "http://localhost:8080",
            "JDBC_URL", "jdbc:postgresql://localhost:5432/app",
            "JDBC_USER", "app",
            "JDBC_PASS", "app");

    @Test
    void create_failsFastWhenAppBaseUriMissing() {
        Map<String, String> env = Map.of("JDBC_URL", "jdbc:postgresql://localhost/app");
        assertThatThrownBy(() -> TestScope.create(Env.of(env)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BASE_URI");
    }

    @Test
    void create_failsFastWhenJdbcUrlMissing() {
        Map<String, String> env = Map.of("APP_BASE_URI", "http://localhost:8080");
        assertThatThrownBy(() -> TestScope.create(Env.of(env)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC_URL");
    }

    @Test
    void create_issuesUniqueTestIds() {
        TestScope a = TestScope.create(Env.of(VALID_ENV));
        TestScope b = TestScope.create(Env.of(VALID_ENV));
        assertThat(a.testId()).isNotEqualTo(b.testId());
        assertThat(a.testId()).matches("t-[0-9a-f]{8}");
    }

    @Test
    void cleanup_isIdempotent() {
        TestScope scope = TestScope.create(Env.of(VALID_ENV));
        scope.cleanup();
        scope.cleanup();   // 두 번째 호출도 예외 없어야 함
    }

    @Test
    void rest_givenIncludesBaggageHeader() {
        TestScope scope = TestScope.create(Env.of(VALID_ENV));
        // RequestSpecification 자체 검증은 불가하므로 helper의 헤더 구성만 확인
        assertThat(scope.rest().baggageHeaderValue()).isEqualTo("test-id=" + scope.testId());
    }
}
