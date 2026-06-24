package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.GuardKind;
import io.graphrag.builder.index.ConstraintExtractor.StateGuard;
import io.graphrag.builder.index.ConstraintExtractor.StateGuardConjunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConstraintExtractor.extractStateGuardConjunctions — AND 복합 가드 검출.
 * REQ-001: 2~3 leaf 완전분류 AND conjunction emit.
 * REQ-002: numeric-param/OR/4+leaf/단일 skip.
 */
class ConstraintExtractorConjunctionTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    /** byStatusTier: status==CONFIRMED && tier==VIP → 2 leaf, 각각 ENUM. */
    @Test
    void detect2Leaf() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        StateGuardConjunction c = conjs.stream()
                .filter(sc -> sc.classFqn().endsWith("StateGuards") && sc.method().equals("byStatusTier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("byStatusTier conjunction not found"));

        assertThat(c.leaves()).hasSize(2);
        assertThat(c.leaves()).allMatch(g -> g.kind() == GuardKind.ENUM);

        StateGuard statusLeaf = c.leaves().stream()
                .filter(g -> g.column().equals("status"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("status leaf not found"));
        assertThat(statusLeaf.positiveConstants()).contains("CONFIRMED");

        StateGuard tierLeaf = c.leaves().stream()
                .filter(g -> g.column().equals("tier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tier leaf not found"));
        assertThat(tierLeaf.positiveConstants()).contains("VIP");
    }

    /** byThree: status==CONFIRMED && getActive() && count>0 → 3 leaf. */
    @Test
    void threeLeafEmit() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        StateGuardConjunction c = conjs.stream()
                .filter(sc -> sc.classFqn().endsWith("StateGuards") && sc.method().equals("byThree"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("byThree conjunction not found"));

        assertThat(c.leaves()).hasSize(3);
        assertThat(c.leaves().stream().map(StateGuard::kind))
                .containsExactlyInAnyOrder(GuardKind.ENUM, GuardKind.BOOLEAN, GuardKind.NUMERIC);
    }

    /** byFour: 4 leaf → conjunction skip, 결과에 없어야 함. */
    @Test
    void fourLeafSkip() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        assertThat(conjs)
                .filteredOn(c -> c.classFqn().endsWith("StateGuards") && c.method().equals("byFour"))
                .isEmpty();
    }

    /**
     * byTemporalActive: isBefore(now()) && getActive()
     * → TEMPORAL leaf op="isBefore" + BOOLEAN leaf (BOOLEAN 오분류 아님).
     */
    @Test
    void temporalFirst() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        StateGuardConjunction c = conjs.stream()
                .filter(sc -> sc.classFqn().endsWith("StateGuards") && sc.method().equals("byTemporalActive"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("byTemporalActive conjunction not found"));

        assertThat(c.leaves()).hasSize(2);

        StateGuard temporalLeaf = c.leaves().stream()
                .filter(g -> g.kind() == GuardKind.TEMPORAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("TEMPORAL leaf not found — isBefore가 BOOLEAN으로 오분류됨"));
        assertThat(temporalLeaf.column()).isEqualTo("check_in_date");
        assertThat(temporalLeaf.op()).isEqualTo("isBefore");

        StateGuard boolLeaf = c.leaves().stream()
                .filter(g -> g.kind() == GuardKind.BOOLEAN)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BOOLEAN leaf (active) not found"));
        assertThat(boolLeaf.column()).isEqualTo("active");
    }

    /** byNumParam: getNights()>=min(PARAM) && getActive() → numeric-param 혼입이라 skip. */
    @Test
    void numericParamSkip() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        assertThat(conjs)
                .filteredOn(c -> c.classFqn().endsWith("StateGuards") && c.method().equals("byNumParam"))
                .isEmpty();
    }

    /** byOr: getActive() && (getCount()>0 || getNights()>0) → OR 혼입이라 skip. */
    @Test
    void orSkip() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        assertThat(conjs)
                .filteredOn(c -> c.classFqn().endsWith("StateGuards") && c.method().equals("byOr"))
                .isEmpty();
    }

    /** 단일 조건(byActive 등) → conjunction이 아니므로 결과에 없어야 함. */
    @Test
    void singleNotConjunction() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);

        assertThat(conjs)
                .filteredOn(c -> c.classFqn().endsWith("StateGuards") && c.method().equals("byActive"))
                .isEmpty();
    }

    /**
     * ablationOff: extractStateGuardConjunctions는 env 플래그와 무관하게 동작해야 한다.
     * (ablation 배선은 Task6 BuilderCli 담당; 이 메서드 자체는 env를 보지 않음.)
     * SAMPLE_SRC에 byStatusTier가 있으므로 결과가 비어있지 않아야 한다.
     */
    @Test
    void ablationOff() {
        List<StateGuardConjunction> conjs = new ConstraintExtractor().extractStateGuardConjunctions(SAMPLE_SRC);
        // 메서드 자체는 env 무관 — byStatusTier가 검출되어야 함
        assertThat(conjs)
                .filteredOn(c -> c.classFqn().endsWith("StateGuards") && c.method().equals("byStatusTier"))
                .isNotEmpty();
    }
}
