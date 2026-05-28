package io.graphrag.builder.staticanalysis.branch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryValueGeneratorTest {

    private final BoundaryValueConfig cfg = BoundaryValueConfig.defaults();

    @Test
    void numeric_int_happy_is_one() {
        assertThat(BoundaryValueGenerator.happy("int", cfg)).isEqualTo("1");
        assertThat(BoundaryValueGenerator.happy("Integer", cfg)).isEqualTo("1");
    }

    @Test
    void numeric_int_variants_are_neg1_zero_maxint() {
        assertThat(BoundaryValueGenerator.variants("Integer", cfg))
                .containsExactly("-1", "0", String.valueOf(Integer.MAX_VALUE));
    }

    @Test
    void string_happy_is_a() {
        assertThat(BoundaryValueGenerator.happy("String", cfg)).isEqualTo("a");
    }

    @Test
    void string_variants_contain_empty() {
        assertThat(BoundaryValueGenerator.variants("String", cfg)).containsExactly("");
    }

    @Test
    void isNumeric_recognises_primitive_and_boxed() {
        List.of("int", "Integer", "long", "Long", "short", "Short",
                "byte", "Byte", "double", "Double", "float", "Float")
            .forEach(t -> assertThat(BoundaryValueGenerator.isNumeric(t))
                    .as("isNumeric(\"%s\")", t).isTrue());
    }

    @Test
    void isStringLike_recognises_String_and_CharSequence() {
        assertThat(BoundaryValueGenerator.isStringLike("String")).isTrue();
        assertThat(BoundaryValueGenerator.isStringLike("CharSequence")).isTrue();
        assertThat(BoundaryValueGenerator.isStringLike("Integer")).isFalse();
    }

    @Test
    void complex_type_yields_empty_variants_and_empty_happy() {
        assertThat(BoundaryValueGenerator.variants("OwnerDto", cfg)).isEmpty();
        assertThat(BoundaryValueGenerator.happy("OwnerDto", cfg)).isEmpty();
    }
}
