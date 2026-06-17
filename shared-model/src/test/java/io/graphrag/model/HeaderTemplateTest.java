package io.graphrag.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class HeaderTemplateTest {
    @Test void expandsNowInSeoulZoneAndPreservesLiterals() {
        Instant fixed = ZonedDateTime.of(2026,6,17,14,30,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        String out = HeaderTemplate.resolve("{{now:yyyyMMddHHmmss}}0900", fixed);
        assertEquals("202606171430050900", out);
    }
    @Test void plainValueUnchanged() {
        assertEquals("Bearer x", HeaderTemplate.resolve("Bearer x", Instant.now()));
    }
    @Test void multiplePlaceholders() {
        Instant fixed = ZonedDateTime.of(2026,1,2,3,4,5,0, ZoneId.of("Asia/Seoul")).toInstant();
        assertEquals("20260102-030405", HeaderTemplate.resolve("{{now:yyyyMMdd}}-{{now:HHmmss}}", fixed));
    }
    @Test void validateThrowsOnInvalidPattern() {
        assertThrows(IllegalArgumentException.class, () -> HeaderTemplate.validate("{{now:zzz-bogus}}"));
    }
}
