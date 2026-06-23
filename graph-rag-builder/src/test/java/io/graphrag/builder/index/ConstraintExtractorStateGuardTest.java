package io.graphrag.builder.index;

import io.graphrag.builder.index.ConstraintExtractor.ComparandKind;
import io.graphrag.builder.index.ConstraintExtractor.GuardKind;
import io.graphrag.builder.index.ConstraintExtractor.StateGuard;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 4 StateGuardOracle 인식기: TEMPORAL/ENUM 상태 의존 가드 추출, pure-input 비교 제외. */
class ConstraintExtractorStateGuardTest {

    private static final Path SAMPLE_SRC = Path.of("src/test/resources/sample-src");

    @Test
    void recognizesTemporalGuard_onGetterColumn() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        StateGuard temporal = guards.stream()
                .filter(g -> g.kind() == GuardKind.TEMPORAL)
                .filter(g -> g.classFqn().endsWith("StateGuards") && g.method().equals("getById"))
                .findFirst().orElseThrow();
        // 컬럼은 isBefore의 target getter(getCheckInDate)에서 유도 — 'before'가 아니어야 한다
        assertThat(temporal.column()).isEqualTo("check_in_date");
        assertThat(temporal.line()).isGreaterThan(0);
    }

    @Test
    void recognizesEnumGuard_withNegatedConstantSet() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        StateGuard enumGuard = guards.stream()
                .filter(g -> g.kind() == GuardKind.ENUM)
                .filter(g -> g.classFqn().endsWith("StateGuards") && g.method().equals("delete"))
                .findFirst().orElseThrow();
        assertThat(enumGuard.column()).isEqualTo("status");
        assertThat(enumGuard.enumType()).endsWith("BookingStatus");
        // status != PENDING && status != CANCELLED → 부정집합 {CANCELLED, PENDING}
        assertThat(enumGuard.negatedConstants()).containsExactly("CANCELLED", "PENDING");
    }

    @Test
    void recognizesEqGuard_withPositiveConstantSet() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);
        // advance: status == PENDING / == CONFIRMED / == CANCELLED → positive={CANCELLED,CONFIRMED,PENDING}
        StateGuard eq = guards.stream()
                .filter(g -> g.kind() == GuardKind.ENUM)
                .filter(g -> g.classFqn().endsWith("StateGuards") && g.method().equals("advance"))
                .findFirst().orElseThrow();
        assertThat(eq.column()).isEqualTo("status");
        assertThat(eq.positiveConstants()).containsExactly("CANCELLED", "CONFIRMED", "PENDING");
        assertThat(eq.negatedConstants()).isEmpty();
    }

    @Test
    void recognizesMixedNeAndEqOnSameColumn() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);
        // mixed: status != PENDING && status == CONFIRMED → 한 가드에 negated=[PENDING]·positive=[CONFIRMED]
        StateGuard mixed = guards.stream()
                .filter(g -> g.kind() == GuardKind.ENUM)
                .filter(g -> g.classFqn().endsWith("StateGuards") && g.method().equals("mixed"))
                .findFirst().orElseThrow();
        assertThat(mixed.negatedConstants()).containsExactly("PENDING");
        assertThat(mixed.positiveConstants()).containsExactly("CONFIRMED");
    }

    @Test
    void booleanGuard_truthy() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byActive: if(b.getActive()) → BOOLEAN, column="active", op="==", comparand="true"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.BOOLEAN)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byActive"))
                .findFirst().orElseThrow(() -> new AssertionError("BOOLEAN guard for byActive not found"));
        assertThat(g.column()).isEqualTo("active");
        assertThat(g.op()).isEqualTo("==");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("true");
    }

    @Test
    void booleanGuard_negated() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byNotActive: if(!b.isActive()) → BOOLEAN, column="active", op="==", comparand="false"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.BOOLEAN)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byNotActive"))
                .findFirst().orElseThrow(() -> new AssertionError("BOOLEAN guard for byNotActive not found"));
        assertThat(g.column()).isEqualTo("active");
        assertThat(g.op()).isEqualTo("==");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("false");
    }

    @Test
    void booleanGuard_isPrefix() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byIsActive: if(b.isActive()) — is-prefix getter 단독, column="active", comparand="true"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.BOOLEAN)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byIsActive"))
                .findFirst().orElseThrow(() -> new AssertionError("BOOLEAN guard for byIsActive(isPrefix) not found"));
        assertThat(g.column()).isEqualTo("active");
        assertThat(g.comparand()).isEqualTo("true");
    }

    @Test
    void nullityGuard_eqNull() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byNote: if(b.getNote() == null) → NULLITY, column="note", op="==", comparand="null"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.NULLITY)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byNote"))
                .findFirst().orElseThrow(() -> new AssertionError("NULLITY guard for byNote not found"));
        assertThat(g.column()).isEqualTo("note");
        assertThat(g.op()).isEqualTo("==");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("null");
    }

    @Test
    void nullityGuard_neNull() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byNoteNe: if(b.getNote() != null) → NULLITY, column="note", op="!=", comparand="null"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.NULLITY)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byNoteNe"))
                .findFirst().orElseThrow(() -> new AssertionError("NULLITY guard for byNoteNe not found"));
        assertThat(g.column()).isEqualTo("note");
        assertThat(g.op()).isEqualTo("!=");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("null");
    }

    @Test
    void numericLiteralGuard_gt() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byCount: if(b.getCount() > 0) → NUMERIC, column="count", op=">", comparand="0"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.NUMERIC)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byCount"))
                .findFirst().orElseThrow(() -> new AssertionError("NUMERIC guard for byCount not found"));
        assertThat(g.column()).isEqualTo("count");
        assertThat(g.op()).isEqualTo(">");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("0");
    }

    @Test
    void numericLiteralGuard_negativeLiteral() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byBalance: if(b.getBalance() >= -5) → NUMERIC, column="balance", op=">=", comparand="-5"
        StateGuard g = guards.stream()
                .filter(sg -> sg.kind() == GuardKind.NUMERIC)
                .filter(sg -> sg.classFqn().endsWith("StateGuards") && sg.method().equals("byBalance"))
                .findFirst().orElseThrow(() -> new AssertionError("NUMERIC guard for byBalance not found"));
        assertThat(g.column()).isEqualTo("balance");
        assertThat(g.op()).isEqualTo(">=");
        assertThat(g.comparandKind()).isEqualTo(ComparandKind.LITERAL);
        assertThat(g.comparand()).isEqualTo("-5");
    }

    @Test
    void numericLiteralGuard_floatExcluded() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // byRate: if(b.getRate() > 1.5) → double 리터럴 → NUMERIC guard emit 안 함
        assertThat(guards)
                .filteredOn(g -> g.kind() == GuardKind.NUMERIC
                        && g.classFqn().endsWith("StateGuards") && g.method().equals("byRate"))
                .isEmpty();
    }

    @Test
    void doesNotRecognizePureInputComparison_asStateGuard() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // id <= 0 은 입력 파라미터 비교 — 어떤 컬럼 상태 가드로도 잡히면 안 된다
        assertThat(guards).noneMatch(g -> "id".equals(g.column()) && g.method().equals("getById"));
        // requested != CANCELLED 는 파라미터 enum 비교(getter 아님) — ENUM state guard로 잡히면 안 된다
        assertThat(guards).noneMatch(g -> g.method().equals("filter"));
        assertThat(guards).allMatch(g -> g.classFqn() != null && g.method() != null);
    }
}
