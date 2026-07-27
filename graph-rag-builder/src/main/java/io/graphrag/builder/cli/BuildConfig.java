package io.graphrag.builder.cli;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.index.SourceRoots;
import io.graphrag.builder.oracle.ClassifierConfig;
import io.graphrag.builder.oracle.LlmOptions;
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
        io.graphrag.model.RequestHeaders requestHeaders,
        List<String> endpointSelectors,
        String traceMode,
        ClassifierConfig classifierConfig,
        boolean noIncremental,
        boolean reflectInstantiate,
        LlmOptions llm,
        SourceRoots sourceRoots,
        /** Phase 0 spike: HTTP 엔드포인트 루프 병렬도. 1 = 순차(기존 동작). */
        int parallelism,
        /**
         * 커버리지 백엔드 식별자. P1-6에서 JaCoCo 백엔드를 전면 제거해 현재는 "pjacoco" 단일 경로다.
         * 필드는 향후 다른 백엔드 도입 여지를 위해 유지하나 빌더는 항상 pjacoco를 사용한다.
         */
        String coverageBackend,
        /** pjacoco includes 패턴 (예: io/example/*). 미지정 시 **\/* (전체 캡처). */
        String sutPkg,
        /**
         * P2-4: pjacoco flush ExecutorService 스레드 수 (--flush-threads).
         * pjacoco는 per-worker-synchronous 모델 — 각 워커가 자기 traceId로 flush+await를 직접 수행하므로
         * 별도 flush 풀이 불필요하다. 이 값은 수락되나 사용되지 않는다 (향후 전략 변경 대비 플래그 유지).
         * 기본 0 = parallelism×2 (하한 parallelism) 로 문서화.
         */
        int flushThreads,
        /**
         * P2-4: pjacoco .exec 파일 생성 대기 타임아웃 (ms). 0이면 PjacocoCoverageBackend 기본값(30_000ms).
         * --exec-await-ms 로 설정한다.
         */
        long execAwaitMs,
        /**
         * F1b: OtelSpanCapture entry-span await 타임아웃 (ms). 0이면 모드별 기본값 적용
         * (순차 8_000ms, 병렬 30_000ms). --sql-await-ms 로 설정한다.
         * 병렬 4 워커 동시 부하에서 OTLP BSP export 지연이 증가하므로 병렬 모드에는 큰 값이 필요하다.
         */
        long sqlAwaitMs,
        /**
         * REQ-018/019/020/035: {@code --triple-candidates <dir>}(nullable). 지정 시 explore 루프가
         * base happy invoke FAILURE인 endpoint에서 이 루트 아래 {@code <endpointId>/promoted/cand-NN}을
         * 재확인·소비한다(design spec §3.2). Task 15부터 attach 모드에도 그대로 배선된다 — attach
         * 안전(REQ-023/024/025 이중 opt-in·역-DELETE 차단·스텁 skip)은 {@link BuilderCli.AttachConfig}의
         * {@code allowSeed}/{@code confirmNonProduction}을 통해 {@code TrialRunner}가 판정한다.
         */
        Path tripleCandidatesRoot) {

    public BuildConfig {
        sutEnv = sutEnv == null ? Map.of() : sutEnv;
        changedFiles = changedFiles == null ? List.of() : changedFiles;
        requestHeaders = requestHeaders == null ? io.graphrag.model.RequestHeaders.empty() : requestHeaders;
        endpointSelectors = endpointSelectors == null ? List.of() : endpointSelectors;
        traceMode = traceMode == null ? "otel" : traceMode;   // 기본 otel (sleuth/none은 명시)
        classifierConfig = classifierConfig == null ? ClassifierConfig.from(Map.of()) : classifierConfig;
        llm = llm == null ? LlmOptions.disabled() : llm;
        sourceRoots = sourceRoots == null ? SourceRoots.single(sutSrc) : sourceRoots;
        if (parallelism < 1) throw new IllegalArgumentException("--parallelism must be >= 1, got: " + parallelism);
        coverageBackend = coverageBackend == null ? "pjacoco" : coverageBackend;
        sutPkg = sutPkg == null ? "**/*" : sutPkg;
        if (flushThreads < 0) throw new IllegalArgumentException("--flush-threads must be >= 0, got: " + flushThreads);
        if (execAwaitMs < 0) throw new IllegalArgumentException("--exec-await-ms must be >= 0, got: " + execAwaitMs);
        if (sqlAwaitMs < 0) throw new IllegalArgumentException("--sql-await-ms must be >= 0, got: " + sqlAwaitMs);
    }

    /** tripleCandidatesRoot를 생략하는 호환 생성자(REQ-018 게이트 비활성 — 기존 canonical 32-arg 호출부 호환). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome,
                       BuilderCli.AttachConfig attach, io.graphrag.model.RequestHeaders requestHeaders,
                       List<String> endpointSelectors, String traceMode,
                       ClassifierConfig classifierConfig, boolean noIncremental, boolean reflectInstantiate,
                       LlmOptions llm, SourceRoots sourceRoots, int parallelism, String coverageBackend,
                       String sutPkg, int flushThreads, long execAwaitMs, long sqlAwaitMs) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, attach, requestHeaders,
                endpointSelectors, traceMode, classifierConfig, noIncremental, reflectInstantiate,
                llm, sourceRoots, parallelism, coverageBackend, sutPkg, flushThreads, execAwaitMs,
                sqlAwaitMs, null);
    }

    /** parallel/pjacoco 옵션을 생략하는 편의 생성자 (llm+sourceRoots까지 받는 26-arg 호출부 호환 — 나머지 기본값). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome,
                       BuilderCli.AttachConfig attach, io.graphrag.model.RequestHeaders requestHeaders,
                       List<String> endpointSelectors, String traceMode,
                       ClassifierConfig classifierConfig, boolean noIncremental, boolean reflectInstantiate,
                       LlmOptions llm, SourceRoots sourceRoots) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, attach, requestHeaders,
                endpointSelectors, traceMode, classifierConfig, noIncremental, reflectInstantiate,
                llm, sourceRoots, 1, null, null, 0, 0L, 0L);
    }

    /** reflectInstantiate·llm 을 생략하는 편의 생성자 (기존 23-arg 호출부 호환 — 둘 다 기본값). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome,
                       BuilderCli.AttachConfig attach, io.graphrag.model.RequestHeaders requestHeaders,
                       List<String> endpointSelectors, String traceMode,
                       ClassifierConfig classifierConfig, boolean noIncremental) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, attach, requestHeaders,
                endpointSelectors, traceMode, classifierConfig, noIncremental, true,
                LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L);
    }

    /** llm 을 생략하는 편의 생성자 (reflectInstantiate 는 받고 llm 만 기본값 — 24-arg 호출부 호환). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome,
                       BuilderCli.AttachConfig attach, io.graphrag.model.RequestHeaders requestHeaders,
                       List<String> endpointSelectors, String traceMode,
                       ClassifierConfig classifierConfig, boolean noIncremental, boolean reflectInstantiate) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, attach, requestHeaders,
                endpointSelectors, traceMode, classifierConfig, noIncremental, reflectInstantiate,
                LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L);
    }

    /** 풀빌드 설정 (증분 옵션 없음). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, null, null, null, false, false, null,
                null, io.graphrag.model.RequestHeaders.empty(), List.of(), "otel", null, false, true,
                LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L);
    }

    /** attach/requestHeaders 를 생략하는 편의 생성자 (기존 17-arg 호출부 호환). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, null, io.graphrag.model.RequestHeaders.empty(),
                List.of(), "otel", null, false, true, LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L);
    }

    /** classifierConfig/noIncremental 를 생략하는 편의 생성자 (기존 21-arg 호출부 호환). */
    public BuildConfig(Path sutSrc, Path sutResources, Path sutJar, Path out,
                       String sutId, String commitSha, DbConfig dbConfig,
                       int budgetRequests, Path manualPathsDir, Path externalStubsDir,
                       Map<String, String> sutEnv, Path incrementalBase, List<String> changedFiles,
                       AuthConfig authConfig, boolean withRedis, boolean withKafka, String sutJavaHome,
                       BuilderCli.AttachConfig attach, io.graphrag.model.RequestHeaders requestHeaders,
                       List<String> endpointSelectors, String traceMode) {
        this(sutSrc, sutResources, sutJar, out, sutId, commitSha, dbConfig,
                budgetRequests, manualPathsDir, externalStubsDir, sutEnv, incrementalBase, changedFiles,
                authConfig, withRedis, withKafka, sutJavaHome, attach, requestHeaders,
                endpointSelectors, traceMode, null, false, true, LlmOptions.disabled(), null, 1, null, null, 0, 0L, 0L);
    }
}
