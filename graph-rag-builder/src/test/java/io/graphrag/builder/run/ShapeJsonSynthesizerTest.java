package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ-006: 형상→minimal valid JSON 공유 헬퍼(seed-row·table·Bean Validation 의존 없음). */
class ShapeJsonSynthesizerTest {

    @Test
    void integerFieldBecomes1() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("available", "Integer")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.get("available").asInt()).isEqualTo(1);
    }

    @Test
    void stringFieldBecomesSamplePrefix() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("name", "java.lang.String")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.get("name").asText()).isEqualTo("sample-name");
    }

    @Test
    void booleanFieldBecomesFalse() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("active", "java.lang.Boolean")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.get("active").asBoolean()).isFalse();
    }

    @Test
    void enumFieldBecomesSortedFirstConstant() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("status", "com.x.Status")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of("com.x.Status", List.of("PENDING", "ACTIVE")))
                .synthesizeBody(shape);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE"); // 정렬 첫 상수
    }

    @Test
    void collectionWrapsSingleElement() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("available", "Integer")), true);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.isArray()).isTrue();
        assertThat(body.get(0).get("available").asInt()).isEqualTo(1);
    }
}
