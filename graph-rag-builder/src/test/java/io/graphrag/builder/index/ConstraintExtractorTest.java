package io.graphrag.builder.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintExtractorTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void extract_collectsBranchConditionsOfHandlerMethod() {
        List<ConstraintExtractor.ConditionSpan> conditions = new ConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.orders.OrderController", "create");

        assertThat(conditions).isNotEmpty();
        assertThat(conditions).anyMatch(c -> c.text().contains("userId() == null"));
        assertThat(conditions).allMatch(c -> c.startLine() > 0 && c.endLine() >= c.startLine());
    }

    @Test
    void extract_unknownMethod_returnsEmpty() {
        List<ConstraintExtractor.ConditionSpan> conditions = new ConstraintExtractor()
                .extract(SAMPLE_SRC, "io.graphrag.sample.orders.OrderController", "nope");
        assertThat(conditions).isEmpty();
    }

    @Test
    void extractConjunctions_multiFieldAndOnly_withEnumNumericString() {
        List<ConstraintExtractor.Conjunction> cs =
                new ConstraintExtractor().extractConjunctions(SAMPLE_SRC);
        // 단일필드 || (nights)는 제외 → Guards.check()에서 conjunction 2개
        List<ConstraintExtractor.Conjunction> inCheck = cs.stream()
                .filter(c -> c.method().equals("check")).toList();
        assertThat(inCheck).hasSize(2);

        ConstraintExtractor.Conjunction vip = inCheck.get(0);   // line 정렬 → 첫 if
        assertThat(vip.atoms()).hasSize(2);
        ConstraintExtractor.Atom a0 = vip.atoms().get(0);
        assertThat(a0.kind()).isEqualTo(ConstraintExtractor.Atom.Kind.ENUM_EQ);
        assertThat(a0.fieldRef()).isEqualTo("tier");
        assertThat(a0.value()).isEqualTo("VIP");
        ConstraintExtractor.Atom a1 = vip.atoms().get(1);
        assertThat(a1.kind()).isEqualTo(ConstraintExtractor.Atom.Kind.NUMERIC);
        assertThat(a1.fieldRef()).isEqualTo("loyalty");
        assertThat(a1.op()).isEqualTo("<");
        assertThat(a1.numLiteral()).isEqualTo(500);

        ConstraintExtractor.Conjunction combo = inCheck.get(1);  // 중첩 && 평탄화 → 3원자
        assertThat(combo.atoms()).hasSize(3);
        assertThat(combo.atoms().stream().map(ConstraintExtractor.Atom::kind))
                .containsExactlyInAnyOrder(
                        ConstraintExtractor.Atom.Kind.ENUM_EQ,
                        ConstraintExtractor.Atom.Kind.STRING_EQ,
                        ConstraintExtractor.Atom.Kind.NUMERIC);
    }
}
