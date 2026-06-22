package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.JoinGuard;
import io.graphrag.builder.index.ConstraintExtractor.JoinKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintExtractorJoinGuardTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void fieldToFieldNumericExtracted() {
        List<JoinGuard> all = new ConstraintExtractor().extractJoinGuards(SAMPLE_SRC);

        assertThat(all).filteredOn(g -> g.classFqn().endsWith("BoundsController"))
                .anySatisfy(g -> {
                    assertThat(g.kind()).isEqualTo(JoinKind.NUMERIC);
                    assertThat(g.leftRef()).isEqualTo("amount");
                    assertThat(g.op()).isEqualTo(">");
                    assertThat(g.rightRef()).isEqualTo("score");
                });
    }

    @Test
    void equalsFieldToFieldExtracted() {
        List<JoinGuard> all = new ConstraintExtractor().extractJoinGuards(SAMPLE_SRC);

        assertThat(all).filteredOn(g -> g.classFqn().endsWith("StringJoinController"))
                .anySatisfy(g -> {
                    assertThat(g.kind()).isEqualTo(JoinKind.STRING);
                    assertThat(g.leftRef()).isEqualTo("a");
                    assertThat(g.op()).isEqualTo("equals");
                    assertThat(g.rightRef()).isEqualTo("b");
                });
    }
}
