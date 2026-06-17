package io.graphrag.sample.orders.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** REQUIRE_AUTH_TIME=true 일 때만 X-AuthorizationTime freshness 강제(기본 비활성 — 기존 e2e 불변). */
@Component
public class AuthTimeInterceptor implements HandlerInterceptor {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Value("${REQUIRE_AUTH_TIME:false}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!enabled) { return true; }
        String t = req.getHeader("X-AuthorizationTime");
        if (t == null || !t.matches("\\d{14}0900") || !fresh(t)) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "stale or missing X-AuthorizationTime");
            return false;
        }
        return true;
    }

    private boolean fresh(String t) {
        try {
            Instant sent = LocalDateTime.parse(t.substring(0, 14), FMT).atZone(SEOUL).toInstant();
            return Math.abs(Duration.between(sent, Instant.now()).toMinutes()) <= 5;
        } catch (Exception e) { return false; }
    }
}
