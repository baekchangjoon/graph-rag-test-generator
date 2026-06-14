package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.run.AuthConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 빌더 실행 설정 (CLI 옵션의 구조화). */
public record BuildConfig(
        Path sutSrc,
        Path sutResources,
        Path sutJar,
        Path out,
        String sutId,
        String commitSha,
        DbConfig dbConfig,
        int budgetRequests,
        Path manualPathsDir,
        Path externalStubsDir,
        Map<String, String> sutEnv,
        Path incrementalBase,
        List<String> changedFiles,
        AuthConfig authConfig,
        boolean withRedis,
        String sutJavaHome) {

    public BuildConfig {
        sutEnv = sutEnv == null ? Map.of() : sutEnv;
        changedFiles = changedFiles == null ? List.of() : changedFiles;
    }

    /** 풀빌드 설정 (증분 옵션 없음). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, null, null, null, false, null);
    }
}
