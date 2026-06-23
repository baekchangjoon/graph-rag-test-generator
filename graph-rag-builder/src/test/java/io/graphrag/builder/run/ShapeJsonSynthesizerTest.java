package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void booleanFieldBecomesTrue() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("active", "java.lang.Boolean")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.get("active").asBoolean()).isTrue();   // 입력 동작 보존: Boolean happy=true
    }

    @Test
    void enumFieldBecomesFirstDeclaredConstant() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("status", "com.x.Status")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of("com.x.Status", List.of("PENDING", "ACTIVE")))
                .synthesizeBody(shape);
        assertThat(body.get("status").asText()).isEqualTo("PENDING"); // 선언순 첫 상수(입력 동작 보존)
    }

    @Test
    void collectionWrapsSingleElement() {
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("available", "Integer")), true);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.isArray()).isTrue();
        assertThat(body.get(0).get("available").asInt()).isEqualTo(1);
    }

    // I1: 응답 합성 경로에서 해소 불가 중첩 객체 DTO 필드는 silent String 폴백이 아니라 loud-fail로.

    @Test
    void nestedObjectFieldThrowsUnsupportedShape() {
        // warehouse 필드의 타입이 객체 FQN(com.x.Warehouse) — 스칼라/시간/enum 어디에도 안 맞음.
        BodyShape shape = new BodyShape("OrderResponse",
                List.of(new BodyShape.BodyField("warehouse", "com.x.Warehouse")), false);
        assertThatThrownBy(() -> new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape))
                .isInstanceOf(ShapeJsonSynthesizer.UnsupportedShapeException.class)
                .hasMessageContaining("com.x.Warehouse");
    }

    @Test
    void nestedObjectElementInCollectionThrowsUnsupportedShape() {
        // collection scalar-element 경로(fields 비어 있고 javaType이 객체 FQN)도 loud-fail.
        BodyShape shape = new BodyShape("com.x.Warehouse", List.of(), true);
        assertThatThrownBy(() -> new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape))
                .isInstanceOf(ShapeJsonSynthesizer.UnsupportedShapeException.class);
    }

    @Test
    void enumSimpleNameFieldStillSynthesizes() {
        // simple-name 폴백으로 enum 매칭되는 객체 FQN은 정상 합성(loud-fail 아님).
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("status", "com.other.Status")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of("com.x.Status", List.of("PENDING", "ACTIVE")))
                .synthesizeBody(shape);
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void stringFieldNeverThrows() {
        // 평탄 String 필드는 객체 FQN이 아니므로 정상 합성.
        BodyShape shape = new BodyShape("X",
                List.of(new BodyShape.BodyField("warehouse", "java.lang.String")), false);
        JsonNode body = new ShapeJsonSynthesizer(Map.of()).synthesizeBody(shape);
        assertThat(body.get("warehouse").asText()).isEqualTo("sample-warehouse");
    }
}
