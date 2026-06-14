package io.graphrag.builder.explore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class InputMutatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<BodyShape.BodyField> FIELDS = List.of(
            new BodyShape.BodyField("quantity", "java.lang.Integer"),
            new BodyShape.BodyField("name", "java.lang.String"),
            new BodyShape.BodyField("contact", "java.lang.String"));

    private static final Map<String, List<FieldConstraint>> CONSTRAINTS = Map.of(
            "quantity", List.of(
                    new FieldConstraint("quantity", Kind.MIN, 1, null),
                    new FieldConstraint("quantity", Kind.MAX, 100, null)),
            "name", List.of(
                    new FieldConstraint("name", Kind.SIZE_MIN, 2, null),
                    new FieldConstraint("name", Kind.SIZE_MAX, 10, null)),
            "contact", List.of(new FieldConstraint("contact", Kind.EMAIL, 0, null)));

    @Test
    void constraintDirected_producesViolationAndEdgeAndBoundMutations() {
        Map<String, Set<Long>> bounds = Map.of("quantity", new TreeSet<>(Set.of(0L, 5L)));

        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, bounds);
        List<String> names = ms.stream().map(InputMutator.Mutation::name).toList();

        assertThat(names).contains(
                "min-violate-quantity", "min-edge-quantity",
                "max-violate-quantity", "max-edge-quantity",
                "size-min-violate-name", "size-min-edge-name",
                "size-max-violate-name", "size-max-edge-name",
                "email-violate-contact",
                "bound-quantity-0", "bound-quantity-5");
    }

    @Test
    void constraintDirected_appliesCorrectValues() {
        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of());

        assertThat(applied(ms, "min-violate-quantity").get("quantity").asLong()).isEqualTo(0);
        assertThat(applied(ms, "max-violate-quantity").get("quantity").asLong()).isEqualTo(101);
        assertThat(applied(ms, "size-min-violate-name").get("name").asText()).isEqualTo("x");
        assertThat(applied(ms, "size-max-violate-name").get("name").asText())
                .isEqualTo("xxxxxxxxxxx");
        assertThat(applied(ms, "email-violate-contact").get("contact").asText())
                .isEqualTo("not-an-email");
    }

    @Test
    void constraintDirected_isDeterministic() {
        List<String> a = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        List<String> b = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        assertThat(a).isEqualTo(b);
    }

    private ObjectNode applied(List<InputMutator.Mutation> ms, String name) {
        InputMutator.Mutation m = ms.stream().filter(x -> x.name().equals(name))
                .findFirst().orElseThrow();
        return m.apply().apply(MAPPER.createObjectNode());
    }
}
