package io.graphrag.dashboard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 옵션 인증. DASHBOARD_TOKEN(=dashboard.token) 설정 시 모든 요청에
 * "Authorization: Bearer 토큰" 요구. 미설정 시(기본, compose 내부망) 통과.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {

    private final String token;

    public TokenAuthFilter(@Value("${dashboard.token:}") String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!token.isBlank()) {
            String header = request.getHeader("Authorization");
            byte[] expected = ("Bearer " + token).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] actual = header == null
                    ? new byte[0] : header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
