package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.BodyShape.BodyField;
import io.graphrag.builder.index.FormFieldBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FormBodySynthesizer: bindingKind별 평면 폼 body 합성(중첩 점-경로·참조 주입·스칼라 폴백). */
class FormBodySynthesizerTest {

    private final FormBodySynthesizer synth = new FormBodySynthesizer(Map.of());

    @Test
    void nestedField_flattensToDotPathScalarKeys() {
        BodyShape command = new BodyShape("Cmd", List.of(
                new BodyField("address", "io.x.Address"),
                new BodyField("quantity", "java.lang.Integer")));
        Map<String, BodyShape> shapes = Map.of("io.x.Address", new BodyShape("io.x.Address", List.of(
                new BodyField("city", "java.lang.String"),
                new BodyField("street", "java.lang.String"))));
        List<FormFieldBinding> bindings = List.of(
                FormFieldBinding.nested("address", "io.x.Address", "io.x.Address"),
                FormFieldBinding.scalar("quantity", "java.lang.Integer"));

        ObjectNode body = (ObjectNode) synth.synthesize(command, shapes, bindings, Map.of(), List.of(), Map.of()).body();

        assertThat(body.has("address")).isFalse();                 // 중첩 객체 키는 두지 않음(formEncode 드롭 회피)
        assertThat(body.has("address.city")).isTrue();             // 평면 점-경로 스칼라
        assertThat(body.has("address.street")).isTrue();
        assertThat(body.get("quantity").isNumber()).isTrue();
    }

    @Test
    void nestedDepth2_recursesWithDottedPrefix() {
        BodyShape command = new BodyShape("Cmd", List.of(new BodyField("a", "io.x.A")));
        Map<String, BodyShape> shapes = Map.of(
                "io.x.A", new BodyShape("io.x.A", List.of(new BodyField("b", "io.x.B"))),
                "io.x.B", new BodyShape("io.x.B", List.of(new BodyField("city", "java.lang.String"))));
        List<FormFieldBinding> bindings = List.of(FormFieldBinding.nested("a", "io.x.A", "io.x.A"));

        ObjectNode body = (ObjectNode) synth.synthesize(command, shapes, bindings, Map.of(), List.of(), Map.of()).body();

        assertThat(body.has("a.b.city")).isTrue();
    }

    @Test
    void emptyNestedPojo_fallsBackToScalarKey() {
        BodyShape command = new BodyShape("Cmd", List.of(new BodyField("opaque", "io.x.Empty")));
        Map<String, BodyShape> shapes = Map.of("io.x.Empty", new BodyShape("io.x.Empty", List.of()));
        List<FormFieldBinding> bindings = List.of(FormFieldBinding.nested("opaque", "io.x.Empty", "io.x.Empty"));

        ObjectNode body = (ObjectNode) synth.synthesize(command, shapes, bindings, Map.of(), List.of(), Map.of()).body();

        // 빈 POJO는 점-경로가 없으므로 스칼라 키로 폴백(미바인딩보다 관측 가능).
        assertThat(body.has("opaque")).isTrue();
        assertThat(body.has("opaque.")).isFalse();
    }

    @Test
    void cyclicNested_isGuardedAndDoesNotRecurseInfinitely() {
        BodyShape command = new BodyShape("Cmd", List.of(new BodyField("self", "io.x.Node")));
        Map<String, BodyShape> shapes = Map.of("io.x.Node",
                new BodyShape("io.x.Node", List.of(new BodyField("next", "io.x.Node"))));
        List<FormFieldBinding> bindings = List.of(FormFieldBinding.nested("self", "io.x.Node", "io.x.Node"));

        // 순환은 깊이/방문 가드로 종료(예외 없이 반환).
        ObjectNode body = (ObjectNode) synth.synthesize(command, shapes, bindings, Map.of(), List.of(), Map.of()).body();
        assertThat(body).isNotNull();
    }

    @Test
    void referenceField_usesRefValueWhenPresent() {
        BodyShape command = new BodyShape("Cmd", List.of(new BodyField("color", "io.x.Color")));
        List<FormFieldBinding> bindings = List.of(
                FormFieldBinding.reference("color", "io.x.Color", "io.x.Color", null));

        ObjectNode body = (ObjectNode) synth.synthesize(
                command, Map.of(), bindings, Map.of("color", "red"), List.of(), Map.of()).body();

        assertThat(body.get("color").asText()).isEqualTo("red");
    }

    @Test
    void referenceField_skippedWhenNoRefValue() {
        BodyShape command = new BodyShape("Cmd", List.of(new BodyField("color", "io.x.Color")));
        List<FormFieldBinding> bindings = List.of(
                FormFieldBinding.reference("color", "io.x.Color", "io.x.Color", null));

        ObjectNode body = (ObjectNode) synth.synthesize(
                command, Map.of(), bindings, Map.of(), List.of(), Map.of()).body();

        assertThat(body.has("color")).isFalse();   // 후보 없음 → skip(스칼라/skip 폴백)
    }

    @Test
    void emptyBindings_treatsAllFieldsAsScalar_noRegression() {
        BodyShape command = new BodyShape("Cmd", List.of(
                new BodyField("customer", "java.lang.String"),
                new BodyField("quantity", "java.lang.Integer")));

        ObjectNode body = (ObjectNode) synth.synthesize(command, Map.of(), List.of(), Map.of(), List.of(), Map.of()).body();

        assertThat(body.has("customer")).isTrue();
        assertThat(body.get("quantity").isNumber()).isTrue();
    }
}
