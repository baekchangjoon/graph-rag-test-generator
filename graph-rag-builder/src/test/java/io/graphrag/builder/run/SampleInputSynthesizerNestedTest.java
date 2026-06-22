package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-011 v3: SampleInputSynthesizer는 dot-path 필드를 LITERAL 키로 방출(form contract 유지).
 * JSON @RequestBody 중첩은 runner 단계(JsonPaths.nestDottedKeys)가 담당한다.
 * FK 카브아웃: "Id"로 끝나지만 dot 포함 → FK probe row 미발화.
 */
class SampleInputSynthesizerNestedTest {

    @Test
    void dottedFields_emitLiteralKeysNotNested() {
        BodyShape shape = new BodyShape("com.example.Dto", List.of(
                new BodyShape.BodyField("address.city", "java.lang.String"),
                new BodyShape.BodyField("shipTo.userId", "java.lang.String")
        ));

        SynthesizedInput result = new SampleInputSynthesizer().synthesize(shape, List.of());

        ObjectNode body = (ObjectNode) result.body();

        // 합성기는 literal dotted 키를 방출해야 함 (form contract)
        assertThat(body.has("address.city")).isTrue();
        assertThat(body.has("shipTo.userId")).isTrue();

        // 중첩 구조는 방출하지 않음 (runner 단계 책임)
        assertThat(body.has("address")).isFalse();
        assertThat(body.has("shipTo")).isFalse();

        // FK 카브아웃: shipTo.userId는 "Id"로 끝나지만 dot 포함 → FK probe row 없어야 함
        assertThat(result.seeds()).isEmpty();
    }
}
