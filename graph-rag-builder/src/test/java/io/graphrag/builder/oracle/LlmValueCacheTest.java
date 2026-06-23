package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LlmValueCacheTest {
    private static final List<BodyShape.BodyField> FIELDS =
            List.of(new BodyShape.BodyField("b", "java.lang.String"),
                    new BodyShape.BodyField("a", "java.lang.String"));

    @Test
    void keyStableUnderFieldOrder() {  // REQ-003
        var reordered = List.of(FIELDS.get(1), FIELDS.get(0));
        assertThat(LlmValueCache.key("e1", "body", FIELDS, "m"))
                .isEqualTo(LlmValueCache.key("e1", "body", reordered, "m"));
    }

    @Test
    void keyChangesOnBodyModelOrEndpoint() {  // REQ-003
        String base = LlmValueCache.key("e1", "body", FIELDS, "m");
        assertThat(LlmValueCache.key("e1", "BODY2", FIELDS, "m")).isNotEqualTo(base);
        assertThat(LlmValueCache.key("e1", "body", FIELDS, "m2")).isNotEqualTo(base);
        assertThat(LlmValueCache.key("e2", "body", FIELDS, "m")).isNotEqualTo(base);
    }

    @Test
    void writeThenReadRoundTripsFromFilesystem(@TempDir Path dir) {  // REQ-004
        var cache = new LlmValueCache(dir);
        cache.write("deadbeef", new LlmFieldValues(Map.of("code", List.of("GOLD-1234"))));
        assertThat(dir.resolve("deadbeef.json")).exists();
        assertThat(cache.read("deadbeef")).isPresent()
                .get().extracting(LlmFieldValues::stringValuesByField)
                .isEqualTo(Map.of("code", List.of("GOLD-1234")));
    }

    @Test
    void readMissReturnsEmpty() {  // REQ-004
        assertThat(new LlmValueCache(Path.of("/tmp")).read("no-such-key-xyz"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void writeFailureIsSwallowed() {  // REQ-015
        var cache = new LlmValueCache(Path.of("/proc/should-not-be-writable-xyz"));
        assertThatCode(() -> cache.write("k", new LlmFieldValues(Map.of("a", List.of("x")))))
                .doesNotThrowAnyException();
    }
}
