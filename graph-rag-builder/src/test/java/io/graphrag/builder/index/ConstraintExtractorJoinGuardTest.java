package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.JoinGuard;
import io.graphrag.builder.index.ConstraintExtractor.JoinKind;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("REQ-006: enum-constant comparisons must not be emitted as NUMERIC JoinGuard")
    void enumConstantComparisonNotExtractedAsJoinGuard() {
        List<JoinGuard> all = new ConstraintExtractor().extractJoinGuards(SAMPLE_SRC);

        // StateGuards contains field == Enum.CONST / field != Enum.CONST comparisons
        // (BookingStatus.PENDING, CONFIRMED, CANCELLED).
        // None of those should appear as a NUMERIC JoinGuard whose leftRef or rightRef
        // is an ALL_CAPS enum constant name.
        assertThat(all).filteredOn(g -> g.kind() == JoinKind.NUMERIC)
                .noneMatch(g -> isUpperCaseConstant(g.leftRef()) || isUpperCaseConstant(g.rightRef()));
    }

    /** Returns true when the name looks like an enum constant (all uppercase, possibly with underscores). */
    private static boolean isUpperCaseConstant(String name) {
        return name != null && !name.isEmpty() && name.equals(name.toUpperCase());
    }
}
