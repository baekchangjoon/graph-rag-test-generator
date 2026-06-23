package io.graphrag.builder.run;

import io.graphrag.builder.index.ConstraintExtractor.GuardKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-007: exploreStateGuardVariants의 kind별 boolean QUERY param gate 결정.
 * <p>
 * TEMPORAL → gate=false, ENUM → gate=true, BOOLEAN/NULLITY/NUMERIC → 미적용(skip).
 * appliesBooleanGate 헬퍼를 직접 단위 테스트한다.
 */
class EndpointExplorationRunnerStateGuardTest {

    @Test
    void gateByKind_temporalFalse() {
        // TEMPORAL 가드: boolean QUERY param을 false로 설정 → gate=false
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.TEMPORAL)).isTrue();
        assertThat(EndpointExplorationRunner.booleanGateValueFor(GuardKind.TEMPORAL)).isFalse();
    }

    @Test
    void gateByKind_enumTrue() {
        // ENUM 가드: boolean QUERY param을 true로 설정 → gate=true
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.ENUM)).isTrue();
        assertThat(EndpointExplorationRunner.booleanGateValueFor(GuardKind.ENUM)).isTrue();
    }

    @Test
    void gateByKind_numericNoOverwrite() {
        // NUMERIC 가드: boolean QUERY param에 gate 미적용(덮어쓰지 않음)
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.NUMERIC)).isFalse();
    }

    @Test
    void gateByKind_booleanNoOverwrite() {
        // BOOLEAN 가드: boolean QUERY param에 gate 미적용
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.BOOLEAN)).isFalse();
    }

    @Test
    void gateByKind_nullityNoOverwrite() {
        // NULLITY 가드: boolean QUERY param에 gate 미적용
        assertThat(EndpointExplorationRunner.appliesBooleanGate(GuardKind.NULLITY)).isFalse();
    }
}
