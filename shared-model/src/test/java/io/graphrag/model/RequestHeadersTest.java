package io.graphrag.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RequestHeadersTest {
    @Test void parsesLinesIgnoringCommentsAndBlanks() {
        RequestHeaders h = RequestHeaders.parse(List.of(
                "# auth headers", "", "X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900",
                "X-Api-Key: abc:123"), true);
        assertTrue(h.onLogin());
        assertEquals(2, h.entries().size());
        assertEquals("abc:123", h.entries().get("X-Api-Key"));   // only first ':' splits
    }
    @Test void resolvedExpandsNowPerCall() {
        RequestHeaders h = RequestHeaders.parse(List.of("X-T: {{now:yyyyMMddHHmmss}}0900"), false);
        Instant fixed = ZonedDateTime.of(2026,6,17,14,30,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        Map<String,String> r = h.resolved(fixed);
        assertEquals("202606171430050900", r.get("X-T"));
    }
    @Test void emptyWhenNull() {
        assertTrue(RequestHeaders.empty().entries().isEmpty());
        assertFalse(RequestHeaders.empty().onLogin());
    }
}
