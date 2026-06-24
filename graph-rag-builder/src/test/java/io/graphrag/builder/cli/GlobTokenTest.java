package io.graphrag.builder.cli;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobTokenTest {
    @Test
    void bracePreservedCommaSplit() {
        assertEquals(List.of("a/b/c/{e,common}"), GlobToken.split("a/b/c/{e,common}"));
    }

    @Test
    void plainCommaSplitsWithStrip() {
        assertEquals(List.of("a", "b"), GlobToken.split("a, b"));
    }

    @Test
    void mixedBraceAndList() {
        assertEquals(List.of("a/{x,y}", "b/**"), GlobToken.split("a/{x,y}, b/**"));
    }

    @Test
    void blanksDropped() {
        assertEquals(List.of("a", "b"), GlobToken.split("a, , b,"));
    }
}
