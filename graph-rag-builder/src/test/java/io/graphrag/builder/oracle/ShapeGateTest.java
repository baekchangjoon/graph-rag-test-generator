package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeGateTest {
    private static final BodyShape SHAPE = new BodyShape("Dto",
            List.of(new BodyShape.BodyField("couponCode", "java.lang.String"),
                    new BodyShape.BodyField("quantity", "int")), false);

    @Test
    void acceptsExistingStringField() {  // REQ-007
        var out = ShapeGate.filter(
                new LlmFieldValues(Map.of("couponCode", List.of("GOLD-1234"))), SHAPE);
        assertThat(out).containsOnlyKeys("couponCode");
        assertThat(out.get("couponCode")).containsExactly("GOLD-1234");
    }

    @Test
    void rejectsNonExistentField() {  // REQ-007
        assertThat(ShapeGate.filter(new LlmFieldValues(Map.of("ghost", List.of("x"))), SHAPE))
                .isEmpty();
    }

    @Test
    void rejectsNonStringField() {  // REQ-007
        assertThat(ShapeGate.filter(new LlmFieldValues(Map.of("quantity", List.of("5"))), SHAPE))
                .isEmpty();
    }
}
