package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-011: dot-path 필드명을 중첩 JSON으로 materialization.
 * "address.city" → {"address":{"city":...}}, flat key "address.city" 없어야 함.
 * "shipTo.userId" → FK 휴리스틱 미발화(dot 포함) + {"shipTo":{"userId":...}} 중첩.
 */
class SampleInputSynthesizerNestedTest {

    @Test
    void nestedHappyAndFkCarveOut() {
        BodyShape shape = new BodyShape("com.example.Dto", List.of(
                new BodyShape.BodyField("address.city", "java.lang.String"),
                new BodyShape.BodyField("shipTo.userId", "java.lang.String")
        ));

        SynthesizedInput result = new SampleInputSynthesizer().synthesize(shape, List.of());

        ObjectNode body = (ObjectNode) result.body();

        // 중첩 구조: address.city → body["address"]["city"]
        assertThat(body.has("address")).isTrue();
        assertThat(body.get("address").get("city").isTextual()).isTrue();

        // 중첩 구조: shipTo.userId → body["shipTo"]["userId"]
        assertThat(body.has("shipTo")).isTrue();
        assertThat(body.get("shipTo").get("userId").isTextual()).isTrue();

        // 평면 키 없어야 함
        assertThat(body.has("address.city")).isFalse();
        assertThat(body.has("shipTo.userId")).isFalse();

        // FK 카브아웃: shipTo.userId는 "Id"로 끝나지만 dot 포함 → FK probe row 없어야 함
        assertThat(result.seeds()).isEmpty();
    }

    @Test
    void nestedBooleanFieldEmitsBooleanNode() {
        BodyShape shape = new BodyShape("com.example.Dto", List.of(
                new BodyShape.BodyField("flags.active", "java.lang.Boolean")
        ));

        SynthesizedInput result = new SampleInputSynthesizer().synthesize(shape, List.of());

        ObjectNode body = (ObjectNode) result.body();

        // 중첩 boolean: flags.active → body["flags"]["active"] 는 JSON boolean (string "true" 아님)
        assertThat(body.has("flags")).isTrue();
        assertThat(body.get("flags").get("active").isBoolean()).isTrue();
        assertThat(body.get("flags").get("active").booleanValue()).isTrue();
    }
}
