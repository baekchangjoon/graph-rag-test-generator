package io.graphrag.builder.env;

import java.util.Map;

/** SUT 기동 부가 옵션 (env 주입 — 소스 무수정 — + SUT 전용 JDK). */
public record SutOptions(
        String javaToolOptions,
        Map<String, String> extraLogLevels,
        Map<String, String> extraEnv,
        String javaHome,
        boolean disableHibernateBatch) {

    /** javaHome 미지정 시 빌더 자신의 JDK로 SUT를 띄운다. */
    public SutOptions(String javaToolOptions, Map<String, String> extraLogLevels,
                      Map<String, String> extraEnv) {
        this(javaToolOptions, extraLogLevels, extraEnv, null, false);
    }

    public SutOptions(String javaToolOptions, Map<String, String> extraLogLevels) {
        this(javaToolOptions, extraLogLevels, Map.of(), null, false);
    }

    public SutOptions(String javaToolOptions, Map<String, String> extraLogLevels,
                      Map<String, String> extraEnv, String javaHome) {
        this(javaToolOptions, extraLogLevels, extraEnv, javaHome, false);
    }

    public static SutOptions none() {
        return new SutOptions("", Map.of(), Map.of(), null, false);
    }
}
