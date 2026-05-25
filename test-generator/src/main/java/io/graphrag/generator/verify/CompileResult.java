package io.graphrag.generator.verify;

import java.util.List;
import java.util.Objects;

/**
 * {@link JavaSourceCompiler} 결과.
 *
 * @param success 컴파일 성공 여부 (오류 없음)
 * @param diagnostics javac 진단 메시지 (오류/경고). success=true 라도 경고는 있을 수 있음
 */
public record CompileResult(boolean success, List<String> diagnostics) {
    public CompileResult {
        diagnostics = List.copyOf(Objects.requireNonNullElse(diagnostics, List.of()));
    }
}
