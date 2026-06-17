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
        boolean withKafka,
        String sutJavaHome,
        BuilderCli.AttachConfig attach,
        io.graphrag.model.RequestHeaders requestHeaders) {

    public BuildConfig {
        sutEnv = sutEnv == null ? Map.of() : sutEnv;
        changedFiles = changedFiles == null ? List.of() : changedFiles;
        requestHeaders = requestHeaders == null ? io.graphrag.model.RequestHeaders.empty() : requestHeaders;
    }

    /** 풀빌드 설정 (증분 옵션 없음). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, null, null, null, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty());
    }

    /** attach/requestHeaders 를 생략하는 편의 생성자 (기존 17-arg 호출부 호환). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, null, io.graphrag.model.RequestHeaders.empty());
    }
}
