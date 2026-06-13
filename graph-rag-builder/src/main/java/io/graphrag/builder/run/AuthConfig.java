package io.graphrag.builder.run;

import java.util.List;

/** JWT 로그인 구성. petclinic ApiBlackBoxTestSupport 패턴의 파라미터화. */
public record AuthConfig(
        String loginPath, String username, String password,
        String tokenField, String headerName, String scheme,
        List<String> publicPaths) {

    public AuthConfig {
        publicPaths = publicPaths == null ? List.of() : publicPaths;
    }

    public String headerValue(String token) {
        return scheme + " " + token;
    }
}
