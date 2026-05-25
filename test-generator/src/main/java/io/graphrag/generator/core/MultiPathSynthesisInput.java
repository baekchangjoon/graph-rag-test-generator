package io.graphrag.generator.core;

import io.graphrag.model.Endpoint;

import java.util.List;
import java.util.Objects;

/**
 * 멀티-path 합성 입력. 한 endpoint에 N개 path → N개 @Test 메소드.
 */
public record MultiPathSynthesisInput(
        Endpoint endpoint,
        List<PathContext> paths,
        String testPackage) {

    public MultiPathSynthesisInput {
        Objects.requireNonNull(endpoint, "endpoint");
        paths = List.copyOf(Objects.requireNonNullElse(paths, List.of()));
        Objects.requireNonNull(testPackage, "testPackage");
    }
}
