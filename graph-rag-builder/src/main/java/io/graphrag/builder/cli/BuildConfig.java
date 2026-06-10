package io.graphrag.builder.cli;

import java.nio.file.Path;
import java.util.Map;

/** 빌더 실행 설정 (CLI 옵션의 구조화). */
public record BuildConfig(
        Path sutSrc,
        Path sutResources,
        Path sutJar,
        Path out,
        String sutId,
        String commitSha,
        String postgresImage,
        int budgetRequests,
        Path manualPathsDir,
        Path externalStubsDir,
        Map<String, String> sutEnv) {

    public BuildConfig {
        sutEnv = sutEnv == null ? Map.of() : sutEnv;
    }
}
