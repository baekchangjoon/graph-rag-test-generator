package io.graphrag.generator.verify;

import java.util.List;
import java.util.Objects;

/**
 * {@link JavaSourceRunner} 결과.
 *
 * @param compiled javac 컴파일 성공 여부
 * @param testsRun 발견되어 실행된 테스트 메소드 수
 * @param failures 실패 메소드 수
 * @param diagnostics 컴파일 진단 + 실행 실패 메시지 통합
 */
public record RunResult(
        boolean compiled,
        long testsRun,
        long failures,
        List<String> diagnostics) {

    public RunResult {
        diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
    }

    public boolean allPassed() {
        return compiled && testsRun > 0 && failures == 0;
    }
}
