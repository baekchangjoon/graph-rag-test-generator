package io.graphrag.builder.index;

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
    void doesNotRecognizePureInputComparison_asStateGuard() {
        List<StateGuard> guards = new ConstraintExtractor().extractStateGuards(SAMPLE_SRC);

        // id <= 0 은 입력 파라미터 비교 — 어떤 컬럼 상태 가드로도 잡히면 안 된다
        assertThat(guards).noneMatch(g -> "id".equals(g.column()) && g.method().equals("getById"));
        // requested != CANCELLED 는 파라미터 enum 비교(getter 아님) — ENUM state guard로 잡히면 안 된다
        assertThat(guards).noneMatch(g -> g.method().equals("filter"));
        assertThat(guards).allMatch(g -> g.classFqn() != null && g.method() != null);
    }
}
