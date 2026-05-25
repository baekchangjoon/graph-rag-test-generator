package io.graphrag.generator.core;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;

import java.util.List;

/**
 * {@link TestSynthesizer} 입력. Phase 0의 단순화된 형태.
 *
 * <p>Phase 1+에서는 full {@code GenerationRequest} 객체로 확장.
 */
public record SynthesisInput(
        Endpoint endpoint,
        List<CapturedSql> capturedSql,
        String testPackage) {

    public SynthesisInput {
        java.util.Objects.requireNonNull(endpoint, "endpoint");
        capturedSql = List.copyOf(java.util.Objects.requireNonNullElse(capturedSql, List.of()));
        java.util.Objects.requireNonNull(testPackage, "testPackage");
    }
}
