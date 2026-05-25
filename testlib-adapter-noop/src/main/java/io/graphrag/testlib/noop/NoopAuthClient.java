package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.AuthClient;

import java.util.Map;
import java.util.UUID;

/**
 * {@code auth_mode=disabled} 환경을 위한 no-op auth client.
 *
 * <p>발급되는 토큰은 placeholder. SUT는 인증 비활성 상태여야 정상 동작.
 */
public final class NoopAuthClient implements AuthClient {

    @Override
    public Token login(String username, String password) {
        return new NoopToken();
    }

    @Override
    public Token clientCredentials(String clientId, String clientSecret) {
        return new NoopToken();
    }

    @Override
    public Token jwtFor(String subject, Map<String, Object> claims) {
        return new NoopToken();
    }

    private static final class NoopToken implements Token {
        private final String raw = "noop-" + UUID.randomUUID();
        @Override public String bearerHeader() { return "Bearer " + raw; }
        @Override public String raw() { return raw; }
    }
}
