package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 컬렉션 body happy-only 합성: 1-element array (element=DTO/scalar/enum). */
class SampleInputSynthesizerCollectionTest {

    @Test
    void dtoCollection() {
        BodyShape shape = new BodyShape(
                "com.example.ItemDto",
                List.of(new BodyShape.BodyField("name", "java.lang.String"),
                        new BodyShape.BodyField("qty", "java.lang.Integer")),
                true);

        SynthesizedInput input = new SampleInputSynthesizer().synthesize(shape, List.of());

        assertThat(input.body()).isInstanceOf(ArrayNode.class);
        assertThat(input.body()).hasSize(1);
        JsonNode element = input.body().get(0);
        assertThat(element.isObject()).isTrue();
        assertThat(element.get("name").asText()).isEqualTo("sample-name");
        assertThat(element.get("qty").asLong()).isEqualTo(1L);
    }

    @Test
    void scalarCollection() {
        BodyShape shape = new BodyShape("java.lang.String", List.of(), true);

        SynthesizedInput input = new SampleInputSynthesizer().synthesize(shape, List.of());

        assertThat(input.body()).isInstanceOf(ArrayNode.class);
        assertThat(input.body()).hasSize(1);
        assertThat(input.body().get(0).isTextual()).isTrue();
    }

    @Test
    void enumCollection() {
        SampleInputSynthesizer synth =
                new SampleInputSynthesizer(Map.of("p.E", List.of("A", "B")));
        BodyShape shape = new BodyShape("p.E", List.of(), true);

        SynthesizedInput input = synth.synthesize(shape, List.of());

        assertThat(input.body()).isInstanceOf(ArrayNode.class);
        assertThat(input.body()).hasSize(1);
        assertThat(input.body().get(0).asText()).isEqualTo("A");
    }

    @Test
    void objectBody() {
        BodyShape shape = new BodyShape(
                "com.example.ItemDto",
                List.of(new BodyShape.BodyField("name", "java.lang.String")),
                false);

        SynthesizedInput input = new SampleInputSynthesizer().synthesize(shape, List.of());

        assertThat(input.body().isObject()).isTrue();
    }
}
