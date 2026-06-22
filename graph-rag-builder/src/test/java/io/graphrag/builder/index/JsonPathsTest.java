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
    void putPathBooleanNode() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "a.b", true);
        assertThat(root.get("a").get("b").isBoolean()).isTrue();
        assertThat(root.get("a").get("b").booleanValue()).isTrue();
    }

    @Test
    void flatNameUnchanged() {
        ObjectNode root = obj();
        JsonPaths.putPath(root, "userId", "u1");
        JsonPaths.putNullPath(root, "score");
        assertThat(root.get("userId").asText()).isEqualTo("u1");
        assertThat(root.get("score").isNull()).isTrue();
    }

    @Test
    void nestDottedKeys_movesOnlyDottedKeysPreservingNodeTypes() {
        ObjectNode root = obj();
        root.put("a.b", "x");
        root.put("c.d.e", 1);
        root.put("flat", true);

        JsonPaths.nestDottedKeys(root);

        // 점-경로 키는 중첩 객체로 이동
        assertThat(root.has("a.b")).isFalse();
        assertThat(root.get("a").get("b").isTextual()).isTrue();
        assertThat(root.get("a").get("b").asText()).isEqualTo("x");

        assertThat(root.has("c.d.e")).isFalse();
        assertThat(root.get("c").get("d").get("e").isIntegralNumber()).isTrue();
        assertThat(root.get("c").get("d").get("e").intValue()).isEqualTo(1);

        // 점 없는 키는 불변
        assertThat(root.get("flat").isBoolean()).isTrue();
        assertThat(root.get("flat").booleanValue()).isTrue();
    }
}
