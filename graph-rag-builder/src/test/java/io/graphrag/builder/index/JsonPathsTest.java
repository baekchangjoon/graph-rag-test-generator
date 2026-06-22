package io.graphrag.builder.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPathsTest {

    private ObjectNode obj() { return Json.mapper().createObjectNode(); }

    @Test
    void putPathMaterializesNonObject() {
        ObjectNode root = obj();
        root.putNull("address");                       // 중간이 NullNode
        JsonPaths.putPath(root, "address.city", "x");
        assertThat(root.get("address").get("city").asText()).isEqualTo("x");
    }

    @Test
    void removePathLeafOnly() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "a.b", 1L);
        JsonPaths.putPath(root, "a.c", 2L);
        JsonPaths.removePath(root, "a.b");
        assertThat(root.get("a").has("b")).isFalse();
        assertThat(root.get("a").get("c").asLong()).isEqualTo(2L);
    }

    @Test
    void flatNameUnchanged() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "userId", "u1");
        JsonPaths.putNullPath(root, "score");
        assertThat(root.get("userId").asText()).isEqualTo("u1");
        assertThat(root.get("score").isNull()).isTrue();
    }
}
