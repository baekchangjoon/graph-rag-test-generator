package io.graphrag.testlib.api;

import java.util.Map;

/**
 * 인증 토큰 발급 어댑터.
 *
 * <p>{@code auth_mode=disabled}일 때는 사용되지 않음. {@code real}일 때만 활성.
 * 어댑터 구현체별로 OAuth2/Form/JWT 등 다양.
 */
public interface AuthClient {

    Token login(String username, String password);

    Token clientCredentials(String clientId, String clientSecret);

    Token jwtFor(String subject, Map<String, Object> claims);

    interface Token {
        /** "Bearer xxx" 형식의 Authorization 헤더 값 */
        String bearerHeader();

        /** raw token 문자열 (JWT 등) */
        String raw();
    }
}
