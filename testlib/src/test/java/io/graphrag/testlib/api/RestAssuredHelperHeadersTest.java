package io.graphrag.testlib.api;

import io.graphrag.model.RequestHeaders;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RestAssuredHelperHeadersTest {
    @Test void resolvedCustomHeadersExposedPerCall() {
        RequestHeaders h = RequestHeaders.parse(
                java.util.List.of("X-AuthorizationTime: {{now:yyyyMMddHHmmss}}0900"), false);
        RestAssuredHelper helper = new RestAssuredHelper(
                "http://localhost:1", "t-1", null, "Authorization", "Bearer", "u", "p", h);
        var resolved = helper.customHeaders(
                java.time.ZonedDateTime.of(2026,6,17,14,30,5,0, java.time.ZoneId.of("Asia/Seoul")).toInstant());
        assertEquals("202606171430050900", resolved.get("X-AuthorizationTime"));
    }
}
