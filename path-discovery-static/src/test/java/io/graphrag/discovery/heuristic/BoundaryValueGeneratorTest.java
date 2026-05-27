package io.graphrag.discovery.heuristic;

import io.graphrag.discovery.HandlerParam;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryValueGeneratorTest {

    @Test
    void numeric_path_param_yields_at_least_happy_zero_negone_max_and_empty() {
        HandlerParam p = new HandlerParam("ownerId", "Integer", HandlerParam.ParamSource.PATH);
        var values = BoundaryValueGenerator.generate(p);
        // Workorder T4 AC: ≥4 variants for numeric params.
        assertThat(values).contains("0", "-1", "2147483647", "");
        assertThat(values.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void unboxed_int_is_also_recognized_as_numeric() {
        HandlerParam p = new HandlerParam("n", "int", HandlerParam.ParamSource.QUERY);
        assertThat(BoundaryValueGenerator.generate(p)).contains("0", "-1");
    }

    @Test
    void long_short_byte_double_float_all_recognized() {
        for (String t : new String[] {"long", "Long", "short", "Short", "byte", "Byte",
                                       "double", "Double", "float", "Float"}) {
            HandlerParam p = new HandlerParam("x", t, HandlerParam.ParamSource.QUERY);
            assertThat(BoundaryValueGenerator.generate(p))
                    .as("type=%s should produce numeric boundary values", t)
                    .contains("0", "-1");
        }
    }

    @Test
    void string_param_only_yields_one_default_value() {
        HandlerParam p = new HandlerParam("name", "String", HandlerParam.ParamSource.QUERY);
        var values = BoundaryValueGenerator.generate(p);
        // Strings get no boundary mutation — that lives in a future fuzzer.
        assertThat(values).hasSize(1);
    }

    @Test
    void output_order_is_deterministic() {
        HandlerParam p = new HandlerParam("n", "Integer", HandlerParam.ParamSource.PATH);
        // Run twice — same iteration order guaranteed by LinkedHashSet.
        var first = BoundaryValueGenerator.generate(p).toArray(new String[0]);
        var second = BoundaryValueGenerator.generate(p).toArray(new String[0]);
        assertThat(first).containsExactly(second);
    }
}
