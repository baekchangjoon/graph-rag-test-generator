package io.graphrag.builder.exploration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * {@link JacocoCoverageScorer} API 표면 검증.
 *
 * <p>주의: in-process unit-test 환경에서는 JaCoCo {@link org.jacoco.core.runtime.LoggerRuntime}
 * 의 probe 캡처가 제한될 수 있다 (JUL bridge 의존). 운영 환경 (예: SUT를 외부 JVM에서 실행)
 * 에서는 정확한 coverage가 측정됨.
 *
 * <p>본 테스트는 다음을 검증:
 * <ol>
 *   <li>scorer 생성/해제가 예외 없이 동작
 *   <li>score()가 non-null {@link CoverageSignature} 반환
 *   <li>signature hash는 hex 64자 (SHA-256)
 *   <li>같은 input은 deterministic하게 같은 signature 반환
 * </ol>
 */
class JacocoCoverageScorerTest {

    @Test
    void scorerLifecycleIsExceptionFree() {
        assertThatNoException().isThrownBy(() -> {
            try (JacocoCoverageScorer scorer = JacocoCoverageScorer.forClass(BranchTarget.class)) {
                scorer.score(cls -> {
                    Object target = cls.getDeclaredConstructor().newInstance();
                    cls.getMethod("classify", int.class).invoke(target, 5);
                });
            }
        });
    }

    @Test
    void signatureHashIsStableHex() {
        try (JacocoCoverageScorer scorer = JacocoCoverageScorer.forClass(BranchTarget.class)) {
            CoverageSignature s = scorer.score(cls -> {
                Object target = cls.getDeclaredConstructor().newInstance();
                cls.getMethod("classify", int.class).invoke(target, 0);
            });
            assertThat(s).isNotNull();
            assertThat(s.hash()).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void sameInvocationIsDeterministic() {
        try (JacocoCoverageScorer scorer = JacocoCoverageScorer.forClass(BranchTarget.class)) {
            CoverageSignature s1 = scorer.score(cls -> {
                Object target = cls.getDeclaredConstructor().newInstance();
                cls.getMethod("classify", int.class).invoke(target, 3);
            });
            CoverageSignature s2 = scorer.score(cls -> {
                Object target = cls.getDeclaredConstructor().newInstance();
                cls.getMethod("classify", int.class).invoke(target, 3);
            });
            assertThat(s1).isEqualTo(s2);
        }
    }

    @Test
    void invocationFailureStillReturnsSignature() {
        try (JacocoCoverageScorer scorer = JacocoCoverageScorer.forClass(BranchTarget.class)) {
            CoverageSignature s = scorer.score(cls -> {
                throw new RuntimeException("boom");
            });
            assertThat(s).isNotNull();
            assertThat(s.hash()).matches("[0-9a-f]{64}");
        }
    }
}
