package io.graphrag.testlib.scope;

import java.util.Map;
import java.util.Objects;

/**
 * testlib 런타임 설정. 환경변수 또는 명시적 Map에서 로드.
 *
 * <p>사용 우선순위: 명시 Map → 시스템 env → default.
 */
public final class Config {

    private final Map<String, String> raw;

    private Config(Map<String, String> raw) {
        this.raw = Map.copyOf(raw);
    }

    /**
     * 명시적 Map에서 설정 생성.
     */
    public static Config from(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new Config(env);
    }

    /**
     * 시스템 환경변수에서 설정 생성.
     */
    public static Config fromSystem() {
        return new Config(System.getenv());
    }

    public String appBaseUri() { return raw.get("APP_BASE_URI"); }
    public String jdbcUrl() { return raw.get("JDBC_URL"); }
    public String jdbcUser() { return raw.get("JDBC_USER"); }
    public String jdbcPassword() { return raw.get("JDBC_PASS"); }
    public String httpMockAdminUri() { return raw.get("HTTP_MOCK_ADMIN"); }
    public String socketMockAdminUri() { return raw.get("SOCKET_MOCK_ADMIN"); }
    public String authBaseUri() { return raw.get("AUTH_BASE_URI"); }
    public String dashboardUrl() { return raw.get("DASHBOARD_URL"); }

    public String httpMockAdapterName() { return raw.getOrDefault("HTTP_MOCK_ADAPTER", "noop"); }
    public String socketMockAdapterName() { return raw.getOrDefault("SOCKET_MOCK_ADAPTER", "noop"); }
    public String authAdapterName() { return raw.getOrDefault("AUTH_ADAPTER", "noop"); }
    public String dashboardAdapterName() { return raw.getOrDefault("DASHBOARD_ADAPTER", "noop"); }

    public String authMode() { return raw.getOrDefault("TEST_AUTH_MODE", "disabled"); }
    public String baggageKey() { return raw.getOrDefault("OTEL_BAGGAGE_KEY", "test-id"); }

    /** 임의 키 raw 조회. */
    public String getRaw(String key) { return raw.get(key); }
}
