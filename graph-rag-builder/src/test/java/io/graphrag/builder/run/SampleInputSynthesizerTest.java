package io.graphrag.builder.run;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleInputSynthesizerTest {

    private static final List<TableSchema> SCHEMA = List.of(
            new TableSchema("users",
                    List.of(new ColumnSchema("id", "VARCHAR", false, true),
                            new ColumnSchema("name", "VARCHAR", false, false)),
                    List.of(), List.of(List.of("id"))),
            new TableSchema("orders",
                    List.of(new ColumnSchema("id", "BIGSERIAL", false, true),
                            new ColumnSchema("user_id", "VARCHAR", false, false),
                            new ColumnSchema("amount", "INT4", false, false),
                            new ColumnSchema("type", "VARCHAR", false, false),
                            new ColumnSchema("status", "VARCHAR", false, false)),
                    List.of(new ForeignKey("user_id", "users", "id")),
                    List.of(List.of("id"))));

    private static final BodyShape SHAPE = new BodyShape("X", List.of(
            new BodyShape.BodyField("userId", "java.lang.String"),
            new BodyShape.BodyField("amount", "java.lang.Integer"),
            new BodyShape.BodyField("type", "java.lang.String")));

    @Test
    void synthesize_fkField_getsSeedRowAndProbeValue() {
        SynthesizedInput input = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);

        assertThat(input.body().get("userId").asText()).isEqualTo("probe-userId");
        assertThat(input.seeds()).hasSize(1);
        SynthesizedInput.SeedRow seed = input.seeds().get(0);
        assertThat(seed.table()).isEqualTo("users");
        assertThat(seed.columns()).containsExactly("id", "name");
        assertThat(seed.values()).containsExactly("probe-userId", "probe");
    }

    @Test
    void synthesize_scalarFields_getDeterministicValues() {
        SynthesizedInput input = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);

        assertThat(input.body().get("amount").asInt()).isEqualTo(1);
        assertThat(input.body().get("type").asText()).isEqualTo("sample-type");
    }

    @Test
    void synthesize_isDeterministic() {
        SynthesizedInput a = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);
        SynthesizedInput b = new SampleInputSynthesizer().synthesize(SHAPE, SCHEMA);
        assertThat(a.body()).isEqualTo(b.body());
        assertThat(a.seeds()).isEqualTo(b.seeds());
    }
}
