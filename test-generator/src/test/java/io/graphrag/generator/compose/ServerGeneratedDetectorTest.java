package io.graphrag.generator.compose;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ServerGeneratedDetectorTest {
    @Test
    void detects_uuid_and_iso8601_andClassifiesPattern() {
        assertThat(ServerGeneratedDetector.looksServerGenerated("3f2504e0-4f89-41d3-9a0c-0305e82c3301")).isTrue();
        assertThat(ServerGeneratedDetector.looksServerGenerated("2026-06-21T10:15:30Z")).isTrue();
        assertThat(ServerGeneratedDetector.looksServerGenerated("hello")).isFalse();
        assertThat(ServerGeneratedDetector.patternType("3f2504e0-4f89-41d3-9a0c-0305e82c3301")).isEqualTo("UUID");
        assertThat(ServerGeneratedDetector.patternType("2026-06-21T10:15:30Z")).isEqualTo("TIMESTAMP");
        assertThat(ServerGeneratedDetector.patternType("hello")).isNull();
    }

    @Test
    void regexFor_returnsJavaStringRegex_thatMatchesItsType() {
        assertThat("3f2504e0-4f89-41d3-9a0c-0305e82c3301".matches(ServerGeneratedDetector.regexFor("UUID"))).isTrue();
        assertThat("2026-06-21T10:15:30Z".matches(ServerGeneratedDetector.regexFor("TIMESTAMP"))).isTrue();
    }
}
