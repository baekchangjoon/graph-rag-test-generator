package io.graphrag.builder.env;

import java.util.Map;

/** SUT 기동 부가 옵션 (모두 env로만 주입 — 소스 무수정). */
public record SutOptions(
        String javaToolOptions,
        Map<String, String> extraLogLevels,
        Map<String, String> extraEnv) {

    public SutOptions(String javaToolOptions, Map<String, String> extraLogLevels) {
        this(javaToolOptions, extraLogLevels, Map.of());
    }

    public static SutOptions none() {
        return new SutOptions("", Map.of(), Map.of());
    }
}
