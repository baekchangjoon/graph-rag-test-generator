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
        Map<String, Set<String>> strings = Map.of("name", new TreeSet<>(Set.of("VIP", "gold")));

        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, bounds, strings);
        List<String> names = ms.stream().map(InputMutator.Mutation::name).toList();

        assertThat(names).contains(
                "min-violate-quantity", "min-edge-quantity",
                "max-violate-quantity", "max-edge-quantity",
                "size-min-violate-name", "size-min-edge-name",
                "size-max-violate-name", "size-max-edge-name",
                "email-violate-contact",
                "bound-quantity-0", "bound-quantity-5",
                "streq-name-VIP", "streq-name-gold");
    }

    @Test
    void constraintDirected_appliesCorrectValues() {
        List<InputMutator.Mutation> ms =
                InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of(), Map.of());

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
        List<String> a = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of(), Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        List<String> b = InputMutator.constraintDirected(FIELDS, CONSTRAINTS, Map.of(), Map.of())
                .stream().map(InputMutator.Mutation::name).toList();
        assertThat(a).isEqualTo(b);
    }

    private ObjectNode applied(List<InputMutator.Mutation> ms, String name) {
        InputMutator.Mutation m = ms.stream().filter(x -> x.name().equals(name))
                .findFirst().orElseThrow();
        return m.apply().apply(MAPPER.createObjectNode());
    }

    private static final List<BodyShape.BodyField> FLOAT_FIELDS = List.of(
            new BodyShape.BodyField("base", "float"),
            new BodyShape.BodyField("surcharge", "java.lang.Float"));

    @Test
    void interFieldReal_setsAllFieldsAsDoubles() {
        List<Map<String, Double>> tuples = List.of(Map.of("base", 48.5, "surcharge", 1.0));
        List<InputMutator.Mutation> ms = InputMutator.interFieldReal(FLOAT_FIELDS, tuples);
        assertThat(ms).hasSize(1);
        ObjectNode out = ms.get(0).apply().apply(MAPPER.createObjectNode());
        assertThat(out.get("base").asDouble()).isEqualTo(48.5);
        assertThat(out.get("surcharge").asDouble()).isEqualTo(1.0);
        assertThat(ms.get(0).name()).contains("base").contains("surcharge");
    }

    @Test
    void interFieldReal_skipsTupleWhenFieldMissing() {
        // surcharge 가 body 필드에 없으면 atomic 튜플 적용 불가 → skip.
        List<BodyShape.BodyField> onlyBase = List.of(new BodyShape.BodyField("base", "float"));
        List<Map<String, Double>> tuples = List.of(Map.of("base", 48.5, "surcharge", 1.0));
        assertThat(InputMutator.interFieldReal(onlyBase, tuples)).isEmpty();
    }

    @Test
    void realBounds_emitsMutationPerCandidate() {
        Map<String, Set<Double>> reals = Map.of("base", new TreeSet<>(Set.of(99.0, 100.0, 101.0)));
        List<InputMutator.Mutation> ms = InputMutator.realBounds(FLOAT_FIELDS, reals);
        assertThat(applied(ms, "realbound-base-99.0").get("base").asDouble()).isEqualTo(99.0);
        assertThat(applied(ms, "realbound-base-100.0").get("base").asDouble()).isEqualTo(100.0);
        assertThat(applied(ms, "realbound-base-101.0").get("base").asDouble()).isEqualTo(101.0);
    }

    @Test
    void enumValues_emitsMutationPerConstant() {
        List<BodyShape.BodyField> fields = List.of(new BodyShape.BodyField("tier", "io.x.Tier"));
        Map<String, List<String>> enums = Map.of("io.x.Tier", List.of("BASIC", "VIP"));
        List<InputMutator.Mutation> ms = InputMutator.enumValues(fields, enums);
        assertThat(applied(ms, "enum-tier-BASIC").get("tier").asText()).isEqualTo("BASIC");
        assertThat(applied(ms, "enum-tier-VIP").get("tier").asText()).isEqualTo("VIP");
    }

    @Test
    void joint_setsAllAtomFieldsSimultaneously() {
        List<BodyShape.BodyField> fields = List.of(
                new BodyShape.BodyField("tier", "io.x.Tier"),
                new BodyShape.BodyField("loyalty", "int"));
        io.graphrag.builder.index.ConstraintExtractor.Conjunction c =
                new io.graphrag.builder.index.ConstraintExtractor.Conjunction(
                        "io.x.Svc", "check", 64, List.of(
                        new io.graphrag.builder.index.ConstraintExtractor.Atom(
                                io.graphrag.builder.index.ConstraintExtractor.Atom.Kind.ENUM_EQ,
                                "tier", "==", 0, "VIP"),
                        new io.graphrag.builder.index.ConstraintExtractor.Atom(
                                io.graphrag.builder.index.ConstraintExtractor.Atom.Kind.NUMERIC,
                                "loyalty", "<", 500, null)));
        List<InputMutator.Mutation> ms = InputMutator.joint(fields, List.of(c));
        assertThat(ms).hasSize(1);
        ObjectNode out = ms.get(0).apply().apply(MAPPER.createObjectNode());
        assertThat(out.get("tier").asText()).isEqualTo("VIP");
        assertThat(out.get("loyalty").asInt()).isEqualTo(499);   // satisfy("<",500)=499
        assertThat(ms.get(0).name()).contains("loyalty").contains("tier");
    }

    @Test
    void joint_skippedWhenAnyAtomFieldAbsentFromBody() {
        List<BodyShape.BodyField> fields = List.of(new BodyShape.BodyField("tier", "io.x.Tier"));
        io.graphrag.builder.index.ConstraintExtractor.Conjunction c =
                new io.graphrag.builder.index.ConstraintExtractor.Conjunction(
                        "io.x.Svc", "check", 64, List.of(
                        new io.graphrag.builder.index.ConstraintExtractor.Atom(
                                io.graphrag.builder.index.ConstraintExtractor.Atom.Kind.ENUM_EQ,
                                "tier", "==", 0, "VIP"),
                        new io.graphrag.builder.index.ConstraintExtractor.Atom(
                                io.graphrag.builder.index.ConstraintExtractor.Atom.Kind.NUMERIC,
                                "loyalty", "<", 500, null)));
        assertThat(InputMutator.joint(fields, List.of(c))).isEmpty();
    }
}
