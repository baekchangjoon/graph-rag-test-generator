package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {
    @Test
    void starVsDoubleStar() {
        assertTrue(GlobMatcher.matches("POST /api/orders/*", "POST /api/orders/x"));
        assertFalse(GlobMatcher.matches("POST /api/orders/*", "POST /api/orders/x/y")); // * 는 / 미횡단
        assertTrue(GlobMatcher.matches("POST /api/orders/**", "POST /api/orders/x/y"));
    }

    @Test
    void idGlobAndQuestionAndBrace() {
        assertTrue(GlobMatcher.matches("post-api-orders-*", "post-api-orders-batch"));
        assertTrue(GlobMatcher.matches("GET /api/{users,orders}/**", "GET /api/users/1"));
        assertTrue(GlobMatcher.matches("get-api-?", "get-api-x"));
    }

    @Test
    void pathStringPortableNoPathOf() {
        // "/"-경로 문자열에 대해 예외 없이 동작(Path.of 미사용 검증 — 매칭만 확인)
        assertTrue(GlobMatcher.matches("*/api/**", "GET /api/x"));
    }

    @Test
    void hasGlobMetaDetectsMetachars() {
        assertTrue(GlobMatcher.hasGlobMeta("a/*"));
        assertTrue(GlobMatcher.hasGlobMeta("a?"));
        assertTrue(GlobMatcher.hasGlobMeta("{a,b}"));
        assertFalse(GlobMatcher.hasGlobMeta("POST /api/orders"));
        assertFalse(GlobMatcher.hasGlobMeta("post-api-orders"));
    }

    @Test
    void malformedGlobWrapped() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GlobMatcher.matches("a/[", "a/x"));
        assertFalse(ex.getMessage().isBlank());
    }
}
