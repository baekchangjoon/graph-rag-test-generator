package io.graphrag.builder.cli;

import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageProbe;
import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.coverage.PjacocoAgent;
import io.graphrag.builder.coverage.PjacocoCoverageBackend;
import io.graphrag.builder.coverage.PjacocoCoverageProbe;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.AttachedComposeEnvironment;
import io.graphrag.builder.env.ComposeInspector;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.ExplorationEnvironment;
import io.graphrag.builder.env.OverrideComposeGenerator;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.SourceRoots;
import io.graphrag.oracle.ReflectiveBodyInstantiator;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.EndpointIndexer;
import io.graphrag.builder.index.EnumConstantExtractor;
import io.graphrag.builder.index.GatewayRouteIndexer;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.KafkaListenerIndexer;
import io.graphrag.builder.index.LiteralCandidateExtractor;
import io.graphrag.builder.index.MapperXmlIndexer;
import io.graphrag.builder.index.ResponseDtoIndexer;
import io.graphrag.builder.index.ResponseStringLiteralExtractor;
import io.graphrag.builder.index.RouterFunctionIndexer;
import io.graphrag.builder.index.SharedSpoonModel;
import io.graphrag.builder.index.ValidationConstraintExtractor;
import io.graphrag.builder.index.WsEndpointIndexer;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.builder.env.SutHandle;
import io.graphrag.builder.oracle.ClassifierConfig;
import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.provenance.FailureDigest;
import io.graphrag.builder.provenance.ProvenanceIndexer;
import io.graphrag.builder.provenance.ProvenanceReport;
import io.graphrag.builder.provenance.TrialRunner;
import io.graphrag.builder.provenance.TripleCandidate;
import io.graphrag.builder.provenance.TripleStore;
import io.graphrag.builder.provenance.TripleSynthesizer;
import io.graphrag.builder.capture.TraceParent;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.builder.run.AuthTokenProvider;
import io.graphrag.builder.run.EndpointExplorationRunner;
import io.graphrag.builder.run.SynthesisMethodFilter;
import io.graphrag.builder.store.IndexCache;
import io.graphrag.builder.store.IndexManifest;
import io.graphrag.builder.store.JsonFileGraphStore;
import io.graphrag.builder.store.StaticIndex;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.RequestHeaders;
import io.graphrag.model.RequiredSeed;
import io.graphrag.model.Json;
import io.graphrag.model.MapperStatement;
import io.graphrag.model.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.AbstractMap;

/**
 * 도구 1 진입점 (Phase 2: 분기 탐색 + MyBatis + 외부 HTTP 캡처).
 * build --sut-src <dir> --sut-jar <jar> --out <graph-dir> --sut-compose <docker-compose.yml>
 *       [--sut-resources <dir>] [--sut-id id] [--commit-sha sha]
 *       [--db-image <image>] [--budget-requests 60] [--manual-paths <dir>]
 *       [--external-stubs <dir>] [--sut-env KEY={{wiremock}}[,KEY2=V2]]
 *       [--incremental-base <prev-graph-dir> --changed-files <list-file>]
 *       [--auth-login-path /api/auth/login --auth-user admin --auth-pass password]
 *       [--auth-token-field token --auth-header Authorization --auth-scheme Bearer]
 *       [--no-incremental|--reindex]
 *       [--llm-oracle [--llm-model <id>] [--llm-backend api|bedrock|cli] [--llm-cli claude|cursor-agent|agy|kiro-cli]]
 * --llm-oracle: LLM 값 오라클 opt-in(엄격검증 필드에 도메인 그럴듯한 문자열 생성). 캐시 우선,
 *   자격증명 없고 캐시 miss면 skip(CI 오프라인). 내부 SUT 전용 권고. 미지정 시 no-op.
 *   --llm-backend: api(기본, ANTHROPIC_API_KEY) | bedrock(AWS 자격증명) | cli(--llm-cli 바이너리).
 *   --llm-cli: claude/cursor-agent/agy(=`-p --model`) | kiro-cli(=`chat --no-interactive --model`).
 *     CLI별 모델명이 다름 — claude: claude-haiku-4-5-20251001, kiro-cli: claude-haiku-4.5|auto 등
 *     (`--llm-model`로 해당 CLI에 맞는 이름 지정).
 * 정적 인덱싱 캐시: 이전 빌드가 있으면 <out>/index-cache/에 Spoon 파싱 결과를 캐시하고,
 * 소스 무변경 시 캐시 복원으로 Spoon 0회 재빌드. --no-incremental으로 캐시 무시 강제 풀 리빌드.
 */
public final class BuilderCli {

    private static final Logger log = LoggerFactory.getLogger(BuilderCli.class);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("coverage")) {
            runCoverageReport(parseArgs(args));
            return;
        }
        if (args.length > 0 && args[0].equals("provenance")) {
            runProvenance(parseArgs(args));
            return;
        }
        if (args.length > 0 && args[0].equals("synthesize-triple")) {
            runSynthesizeTriple(parseArgs(args));
            return;
        }
        if (args.length > 0 && args[0].equals("trial")) {
            int exitCode = runTrial(parseArgs(args));
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }
        Map<String, String> options = parseArgs(args);
        SourceRoots sourceRoots = buildSourceRoots(options);
        Path sutSrc = sourceRoots.primary();
        String manualPaths = options.get("--manual-paths");
        String externalStubs = options.get("--external-stubs");
        String incrementalBase = options.get("--incremental-base");
        String changedFilesList = options.get("--changed-files");

        String sutComposeStr = options.get("--sut-compose");
        if (sutComposeStr == null) {
            throw new IllegalArgumentException("--sut-compose <docker-compose.yml> is required");
        }
        DbConfig dbConfig = ComposeInspector.detectDb(
                Path.of(sutComposeStr), options.get("--db-service"));
        if (options.containsKey("--db-image")) {
            dbConfig = new DbConfig(dbConfig.type(), options.get("--db-image"),
                    dbConfig.dbName(), dbConfig.user(), dbConfig.password());
        }

        AttachConfig attach = options.containsKey("--attach")
                ? new AttachConfig(
                        Path.of(sutComposeStr),
                        required(options, "--app-service"),
                        Integer.parseInt(options.getOrDefault("--app-container-port", "8080")),
                        Integer.parseInt(required(options, "--app-port")),
                        Integer.parseInt(coveragePortOption(options)),
                        required(options, "--jdbc-url"),
                        options.get("--kafka-bootstrap"),
                        options.getOrDefault("--health-path", "/actuator/health"),
                        Integer.parseInt(options.getOrDefault("--ready-timeout", "120")),
                        parseCsv(options.get("--capture-services")))
                : null;

        AuthConfig authConfig = options.containsKey("--auth-login-path")
                ? new AuthConfig(options.get("--auth-login-path"),
                        options.getOrDefault("--auth-user", "admin"),
                        options.getOrDefault("--auth-pass", "password"),
                        options.getOrDefault("--auth-token-field", "token"),
                        options.getOrDefault("--auth-header", "Authorization"),
                        options.getOrDefault("--auth-scheme", "Bearer"),
                        java.util.List.of())
                : null;

        io.graphrag.model.RequestHeaders requestHeaders = options.containsKey("--request-headers-file")
                ? io.graphrag.model.RequestHeaders.parse(
                        Files.readAllLines(Path.of(options.get("--request-headers-file"))),
                        options.containsKey("--request-headers-on-login"))
                : io.graphrag.model.RequestHeaders.empty();

        List<String> endpointSelectors = List.of();
        if (options.containsKey("--endpoint")) {
            endpointSelectors = GlobToken.split(options.get("--endpoint"));
            if (endpointSelectors.isEmpty()) {
                throw new IllegalArgumentException("--endpoint given but no non-blank spec(s) provided");
            }
        }

        ClassifierConfig classifierConfig = ClassifierConfig.from(options);

        Path sutResources = options.containsKey("--sut-resources")
                ? Path.of(options.get("--sut-resources"))
                : null;   // null → resourceDirs が per-root sibling resources를 자동 해석 (REQ-011/015)
        BuildConfig config = new BuildConfig(
                sutSrc,
                sutResources,
                Path.of(required(options, "--sut-jar")),
                Path.of(required(options, "--out")),
                options.getOrDefault("--sut-id", "sut"),
                options.getOrDefault("--commit-sha", "unknown"),
                dbConfig,
                Integer.parseInt(options.getOrDefault("--budget-requests", "60")),
                manualPaths == null ? null : Path.of(manualPaths),
                externalStubs == null ? null : Path.of(externalStubs),
                parseEnvPairs(options.get("--sut-env")),
                incrementalBase == null ? null : Path.of(incrementalBase),
                changedFilesList == null ? null
                        : Files.readAllLines(Path.of(changedFilesList)).stream()
                                .filter(line -> !line.isBlank()).toList(),
                authConfig,
                options.containsKey("--with-redis"),
                options.containsKey("--with-kafka"),
                options.get("--sut-java-home"),
                attach,
                requestHeaders,
                endpointSelectors,
                traceMode(options.get("--trace-mode")),
                classifierConfig,
                options.containsKey("--no-incremental") || options.containsKey("--reindex"),
                !options.containsKey("--no-reflect-instantiate"),
                new io.graphrag.builder.oracle.LlmOptions(
                        options.containsKey("--llm-oracle"),
                        options.get("--llm-model"),
                        options.get("--llm-backend"),
                        options.get("--llm-cli")),
                sourceRoots,
                Integer.parseInt(options.getOrDefault("--parallelism", "1")),
                "pjacoco",   // P1-6: JaCoCo 백엔드 제거 — 항상 pjacoco (--coverage-backend 플래그 폐기)
                options.get("--sut-pkg"),
                // P2-4: flush 스레드 수 (per-worker-sync 모델에서는 미사용; 플래그 수락만)
                Integer.parseInt(options.getOrDefault("--flush-threads", "0")),
                // P2-4: .exec await 타임아웃 ms (0 = PjacocoCoverageBackend 기본 30_000ms)
                Long.parseLong(options.getOrDefault("--exec-await-ms", "0")),
                // F1b: OTLP entry-span await 타임아웃 ms (0 = 모드별 기본: 순차 8_000ms, 병렬 30_000ms)
                Long.parseLong(options.getOrDefault("--sql-await-ms", "0")));

        GraphAsset asset = build(config);
        log.info("graph saved: {} endpoints, {} paths, {} sql, {} http, {} tables, {} mappers -> {}",
                asset.endpoints().size(), asset.paths().size(), asset.sql().size(),
                asset.httpCalls().size(), asset.tables().size(), asset.mappers().size(),
                config.out().resolve("graph.json"));
    }

    public static GraphAsset build(BuildConfig config) throws Exception {
        log.info("indexing endpoints from {}", config.sutSrc());
        StaticIndex si = staticIndexWithCache(config);
        IndexResult index = si.index();
        WsIndexResult wsIndex = si.ws();
        KafkaIndexResult kafkaIndex = si.kafka();
        List<MapperStatement> mappers = si.mappers();
        List<Set<String>> responseDtoFieldSets = si.responseDtoFieldSets();
        Map<String, List<String>> enumConstants = si.enumConstants();
        List<io.graphrag.builder.index.ExternalCallSite> callSites = si.callSites();
        Map<String, Map<String, List<String>>> stringLiteralsByDto = si.stringLiteralsByDto();
        log.info("found {} endpoint(s), {} mapper statement(s), {} response dto shape(s), {} external call site(s)",
                index.endpoints().size(), mappers.size(), responseDtoFieldSets.size(), callSites.size());

        IncrementalPlan plan = IncrementalPlan.exploreAll();
        if (!config.endpointSelectors().isEmpty()) {
            Set<String> ids = EndpointSelector.resolve(config.endpointSelectors(),
                    index.endpoints(), wsIndex.endpoints(), kafkaIndex.consumers());
            GraphAsset base = config.incrementalBase() != null
                    ? new JsonFileGraphStore(config.incrementalBase()).load() : null;
            plan = new IncrementalBuildPlanner().planForEndpoints(base, ids,
                    index.endpoints(), wsIndex.endpoints(), kafkaIndex.consumers());
            if (config.changedFiles() != null && !config.changedFiles().isEmpty()) {
                log.warn("--endpoint overrides --changed-files (explicit endpoint selection)");
            }
            if (base == null) {
                log.warn("partial graph: only endpoint(s) {} explored (no --incremental-base)", ids);
            } else {
                log.info("endpoint selection: re-explore {}, carry over the rest from base", ids);
            }
        } else if (config.incrementalBase() != null) {
            GraphAsset previous = new JsonFileGraphStore(config.incrementalBase()).load();
            plan = new IncrementalBuildPlanner().plan(previous, config.changedFiles(),
                    index.endpoints(), wsIndex.endpoints());
            log.info("incremental build: re-explore {}, carry over {} path(s)",
                    plan.exploreIds(), plan.carriedPaths().size());
        }

        Map<String, String> mybatisLogLevels = new LinkedHashMap<>();
        mappers.forEach(m -> mybatisLogLevels.put(m.namespace(), "TRACE"));

        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> sql = new ArrayList<>();
        List<CapturedHttpCall> httpCalls = new ArrayList<>();
        List<io.graphrag.model.WsExchange> wsExchanges = new ArrayList<>();
        List<io.graphrag.model.KafkaExchange> kafkaExchanges = new ArrayList<>();
        List<RequiredSeed> allSeeds = new ArrayList<>();
        List<ExplorationReport.EndpointExploration> reportEntries = new ArrayList<>();
        // Fix 3: accumulate UnsupportedShape entries for REQ-006/REQ-008 loud-failure invariant
        List<ExplorationReport.UnsupportedShape> unsupportedShapes = new ArrayList<>();
        // SUT 전체 도달 분기 집계: 전 엔드포인트가 커버한 whole-app 분기 합집합
        Set<io.graphrag.model.BranchRef> coveredAppBranches = new LinkedHashSet<>();
        // 탐색 전체의 line+branch 집계용: 엔드포인트별 누적 exec를 OR 병합한 run-wide 스토어.
        org.jacoco.core.data.ExecutionDataStore runWideExec = new org.jacoco.core.data.ExecutionDataStore();
        List<io.graphrag.model.CapturedEventEmit> capturedEventEmits = new ArrayList<>();
        ExplorationAccumulators acc = new ExplorationAccumulators(
                paths, sql, httpCalls, wsExchanges, kafkaExchanges, allSeeds, reportEntries,
                coveredAppBranches, runWideExec, capturedEventEmits, unsupportedShapes);

        Path workDir = Files.createDirectories(config.out().resolve("work"));
        OtelAgent otel = OtelAgent.prepare(workDir);
        PjacocoAgent pjacoco = PjacocoAgent.prepare(workDir);

        ExplorationResult result;
        if (config.attach() != null) {
            result = runAttached(config, pjacoco, otel, workDir, mybatisLogLevels,
                    index, wsIndex, kafkaIndex, mappers, responseDtoFieldSets, plan, enumConstants,
                    callSites, stringLiteralsByDto, acc);
        } else {
            // OTel → pjacoco (per-trace 커버리지; P1-6에서 jacoco tcpserver 백엔드 제거)
            int coverageControlPort = freePort();
            Path pjacocoExecDir = Files.createDirectories(workDir.resolve("pjacoco-exec"));
            // sleuth: OTEL javaagent 미부착(레거시 brave.Tracing 빈 충돌 회피) — pjacoco만. 그 외: otel agent 포함.
            boolean analysisSleuthMode = "sleuth".equals(config.traceMode());
            String pjacocoJto = pjacoco.javaToolOptions(
                    pjacocoExecDir, coverageControlPort, config.sutPkg(), config.sutSrc(), true);
            String javaToolOptions = analysisSleuthMode
                    ? pjacocoJto
                    : otel.javaToolOptions() + " " + pjacocoJto;
            log.info("coverage backend: pjacoco (control port {})", coverageControlPort);
            SutOptions sutOptions = new SutOptions(
                    javaToolOptions,
                    mybatisLogLevels,
                    otel.env(config.sutId()),
                    config.sutJavaHome());
            try (AnalysisEnvironment env =
                    new AnalysisEnvironment(config.dbConfig(), config.withRedis(), config.withKafka(),
                            io.graphrag.builder.env.TraceKey.forMode(config.traceMode()))) {
                boolean otelSqlCapture = "otel".equals(config.traceMode());
                env.start(config.sutJar(), workDir, sutOptions,
                        config.externalStubsDir(), config.sutEnv(),
                        otelSqlCapture ? otel : null, config.sutId());
                env.coverageEndpoint("localhost", coverageControlPort);
                // P1-3: CoverageProbe 생성 — pjacoco per-trace 백엔드 (P1-6에서 jacoco 제거)
                // P2-4: execAwaitMs=0이면 기본 30_000ms, 양수이면 CLI --exec-await-ms 값 사용
                long execAwaitMs = config.execAwaitMs() > 0 ? config.execAwaitMs() : 30_000L;
                if (config.flushThreads() > 0) {
                    log.info("P2-4: --flush-threads={} accepted (per-worker-sync 모델 — flush 풀 불필요; 향후 전략 변경 대비 수락)",
                            config.flushThreads());
                }
                log.info("P2-4: pjacoco exec-await timeout={}ms", execAwaitMs);
                CoverageProbe probe = new PjacocoCoverageProbe(new PjacocoCoverageBackend(
                        "localhost", coverageControlPort, pjacocoExecDir, execAwaitMs));
                try {
                    result = explore(env, config, index, wsIndex, kafkaIndex, mappers,
                            responseDtoFieldSets, plan, enumConstants, callSites, stringLiteralsByDto, acc, probe);
                } finally {
                    probe.shutdown();
                }
            }
        }
        int totalAppBranches = result.totalAppBranches();
        List<TableSchema> tables = result.tables();

        paths.addAll(plan.carriedPaths());
        sql.addAll(plan.carriedSql());
        httpCalls.addAll(plan.carriedHttpCalls());
        wsExchanges.addAll(plan.carriedWsExchanges());
        kafkaExchanges.addAll(plan.carriedKafkaExchanges());
        allSeeds.addAll(plan.carriedSeeds());
        capturedEventEmits.addAll(plan.carriedEventEmits());

        mergeManualPaths(config.manualPathsDir(), paths);

        int solverRelevantMissedTotal = reportEntries.stream()
                .mapToInt(ExplorationReport.EndpointExploration::solverRelevantMissed).sum();
        log.info("solver-relevant still-missing branches (concolic-return trigger): {}",
                solverRelevantMissedTotal);
        List<String> coveredAppClasses = coveredAppBranches.stream()
                .map(io.graphrag.model.BranchRef::classFqn).distinct().sorted().toList();
        Files.writeString(config.out().resolve("exploration-report.json"),
                Json.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ExplorationReport(
                                reportEntries, coveredAppBranches.size(), totalAppBranches,
                                coveredAppClasses, unsupportedShapes)));

        io.graphrag.builder.oracle.ClassifierConfig cc = config.classifierConfig();
        // 에러 계약 디스크립터는 error-envelope SUT(--error-when-present 지정)일 때만 영속한다.
        // StatusOnly SUT는 FAILURE 응답 body에 statusField가 없어 단언이 거짓이 되므로 null로 둔다.
        boolean envelope = cc.errorWhenPresent() != null && !cc.errorWhenPresent().isEmpty();
        String statusField = envelope ? cc.semanticStatusField() : null;
        String detailField = envelope ? cc.errorDetailField() : null;
        String detailContains = envelope ? cc.errorDetailContains() : null;
        GraphAsset asset = new GraphAsset(config.sutId(), config.commitSha(),
                index.endpoints(), paths, sql, tables, mappers, httpCalls,
                wsIndex.endpoints(), wsExchanges, kafkaIndex.consumers(), kafkaExchanges, allSeeds,
                capturedEventEmits,
                statusField, detailField, detailContains);
        new JsonFileGraphStore(config.out()).save(asset);
        new io.graphrag.builder.store.PartitionedGraphStore(config.out()).save(asset);
        if (shouldWriteCoverageReport(config.out())) {
            io.graphrag.builder.coverage.CoverageByPathReport.write(asset, config.out());
        }
        return asset;
    }

    /** work/pjacoco-exec 디렉터리에 .exec 파일이 하나 이상 존재할 때만 true를 반환한다. */
    static boolean shouldWriteCoverageReport(Path outDir) {
        Path execDir = outDir.resolve("work/pjacoco-exec");
        if (!Files.isDirectory(execDir)) return false;
        try (var s = Files.newDirectoryStream(execDir, "*.exec")) {
            return s.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    /** 정적 인덱싱 산출물 묶음(직렬화는 Task 4에서 record로 승격). */
    record StaticIndexBundle(IndexResult index, WsIndexResult ws, KafkaIndexResult kafka,
            List<MapperStatement> mappers, List<Set<String>> responseDtoFieldSets,
            Map<String, List<String>> enumConstants,
            List<io.graphrag.builder.index.ExternalCallSite> callSites,
            Map<String, Map<String, List<String>>> stringLiteralsByDto) {
    }

    /** 정적 인덱싱 블록: SUT 소스를 1회 파싱해 모든 Spoon 인덱서가 공유. (테스트 훅 겸용) */
    static StaticIndexBundle indexStatically(Path sutSrc, Path sutResources, AuthConfig authConfig) {
        return indexStatically(SourceRoots.single(sutSrc),
                Files.isDirectory(sutResources) ? List.of(sutResources) : List.<Path>of(), authConfig);
    }

    /** 멀티 루트 정적 인덱싱: 공유 Spoon 모델 + 멀티 resources MapperXml 순회. (REQ-001/002/019) */
    static StaticIndexBundle indexStatically(SourceRoots roots, List<Path> resourceDirs, AuthConfig authConfig) {
        spoon.reflect.CtModel model = SharedSpoonModel.build(roots);
        IndexResult index = new EndpointIndexer().index(model, authConfig);
        IndexResult functional = new RouterFunctionIndexer().index(model);
        if (!functional.endpoints().isEmpty()) {
            log.info("found {} functional route(s) (RouterFunction)", functional.endpoints().size());
            index = index.merge(functional);
        }
        IndexResult gateway = new GatewayRouteIndexer().index(model);
        if (!gateway.endpoints().isEmpty()) {
            log.info("found {} gateway route(s) (RouteLocator)", gateway.endpoints().size());
            index = index.merge(gateway);
        }
        WsIndexResult ws = new WsEndpointIndexer().index(model);
        KafkaIndexResult kafka = new KafkaListenerIndexer().index(model);
        ResponseDtoIndexer responseDtoIndexer = new ResponseDtoIndexer();
        List<Set<String>> dto = responseDtoIndexer.extract(model);
        List<io.graphrag.builder.index.ExternalCallSite> callSites =
                responseDtoIndexer.extractCallSites(model);
        Map<String, List<String>> enums = new EnumConstantExtractor().extract(model);
        Map<String, Map<String, List<String>>> stringLiterals =
                new ResponseStringLiteralExtractor().extract(model, callSites);
        List<MapperStatement> mappers = new ArrayList<>();
        for (Path resDir : resourceDirs) {
            if (Files.isDirectory(resDir)) {
                mappers.addAll(new MapperXmlIndexer().index(resDir));   // REQ-019 멀티 resources
            }
        }
        return new StaticIndexBundle(index, ws, kafka, mappers, dto, enums, callSites, stringLiterals);
    }

    /** 테스트 전용 단순 오버로드. */
    static StaticIndexBundle indexStatically(Path sutSrc) {
        return indexStatically(sutSrc, sutSrc.resolveSibling("resources"), null);
    }

    /** 캐시 우선 정적 인덱싱: 신선하면 복원(Spoon 0회), 미스 또는 noIncremental이면 풀 리빌드 후 저장. */
    static StaticIndex staticIndexWithCache(BuildConfig config) {
        Path cacheDir = config.out().resolve("index-cache");
        SourceRoots roots = config.sourceRoots();
        // resources 결정 한 곳에 위임(REQ-011): config.sutResources()는 --sut-resources 명시 시 그 값,
        // 미지정 시 null(Task 11). null → resourceDirs 가 전 parseRoots sibling resources 순회.
        List<Path> resourceDirs = SutSrcResolver.resourceDirs(roots, config.sutResources());
        IndexManifest current = IndexCache.scan(roots, resourceDirs, config.authConfig());
        if (!config.noIncremental()) {
            Optional<StaticIndex> hit = IndexCache.load(cacheDir, current);
            if (hit.isPresent()) {
                log.info("static index: cache hit (no source change) — skipping Spoon parse");
                return hit.get();
            }
        }
        StaticIndexBundle b = indexStatically(roots, resourceDirs, config.authConfig());
        StaticIndex result = new StaticIndex(b.index(), b.ws(), b.kafka(), b.mappers(),
                b.responseDtoFieldSets(), b.enumConstants(), b.callSites(), b.stringLiteralsByDto());
        IndexCache.save(cacheDir, current, result);
        return result;
    }

    /** explore()가 채우는 mutable 누적기. */
    private record ExplorationAccumulators(
            List<ExploredPath> paths,
            List<CapturedSql> sql,
            List<CapturedHttpCall> httpCalls,
            List<io.graphrag.model.WsExchange> wsExchanges,
            List<io.graphrag.model.KafkaExchange> kafkaExchanges,
            List<RequiredSeed> allSeeds,
            List<ExplorationReport.EndpointExploration> reportEntries,
            Set<io.graphrag.model.BranchRef> coveredAppBranches,
            org.jacoco.core.data.ExecutionDataStore runWideExec,
            List<io.graphrag.model.CapturedEventEmit> capturedEventEmits,
            List<ExplorationReport.UnsupportedShape> unsupportedShapes) {
    }

    /** attach 모드: 사용자 compose + 생성 override 로 SUT를 띄우고 동일한 explore()를 돌린다. */
    private static ExplorationResult runAttached(BuildConfig config, PjacocoAgent pjacoco,
            OtelAgent otel, Path workDir,
            Map<String, String> mybatisLogLevels, IndexResult index,
            io.graphrag.builder.index.WsIndexResult wsIndex,
            io.graphrag.builder.index.KafkaIndexResult kafkaIndex, List<MapperStatement> mappers,
            List<Set<String>> responseDtoFieldSets, IncrementalPlan plan,
            Map<String, List<String>> enumConstants,
            List<io.graphrag.builder.index.ExternalCallSite> callSites,
            Map<String, Map<String, List<String>>> stringLiteralsByDto,
            ExplorationAccumulators acc) throws Exception {
        AttachConfig at = config.attach();
        Path agentsDir = Files.createDirectories(workDir.resolve("agents"));

        // otel jar는 항상 필요 (otel-javaagent.jar mount)
        Files.copy(otel.agentJar(), agentsDir.resolve("otel-javaagent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        int coverageContainerPort = 6300;   // 컨테이너 내부 pjacoco control 포트
        String mode = config.traceMode();
        boolean otelSqlCapture = "otel".equals(mode);
        boolean sleuthMode = "sleuth".equals(mode);

        // OTel → pjacoco 순서. (P1-6에서 jacoco tcpserver 백엔드 제거 — pjacoco per-trace 단일 경로.)
        Files.copy(pjacoco.agentJar(), agentsDir.resolve("pjacoco-agent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(workDir.resolve("pjacoco-exec"));
        // 컨테이너 내부 pjacoco-exec는 workDir 볼륨 마운트로 호스트에 쓴다 → destfile=호스트 경로 그대로 사용
        String pjacocoJto = pjacoco.containerJavaToolOptions(
                "/grb-agents/pjacoco-agent.jar",
                Path.of("/grb-pjacoco-exec"),
                coverageContainerPort, config.sutPkg(), config.sutSrc(), true);
        // 컨테이너 SUT의 OTEL exporter / 외부 HTTP가 host.docker.internal(IPv4+IPv6 둘 다 등록)로 호스트에
        // 도달해야 한다. host-gateway의 IPv6 항목으로는 호스트의 IPv4 리시버에 닿지 못하므로(IPv6=000),
        // SUT JVM을 IPv4 스택으로 고정해 host.docker.internal을 IPv4로 해소시킨다(컨테이너→호스트 OTLP/HTTP 안정).
        String netJto = "-Djava.net.preferIPv4Stack=true";
        String jto = sleuthMode
                ? netJto + " -javaagent:/grb-agents/otel-javaagent.jar " + pjacocoJto + " " + OverrideComposeGenerator.ENCODING_JTO
                : netJto + " -javaagent:/grb-agents/otel-javaagent.jar " + pjacocoJto;
        log.info("coverage backend: pjacoco (attach, container control port {})", coverageContainerPort);

        // host-gateway 는 외부 HTTP 캡처(모든 attach 모드)와 OTLP receiver 도달에 모두 필요 — 항상 1회 점검.
        warnIfHostGatewayUnsupported();
        io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver = null;
        // sleuth egress: 호스트에 Zipkin 리시버를 띄우고 SUT가 host.docker.internal로 export하게 한다.
        // EgressCollector.forMode(env)가 이를 집어 otel(otlpReceiver)과 동일한 egress 발견 경로를 탄다.
        io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver zipkinReceiver = null;
        Map<String, String> otelEnv;
        if (otelSqlCapture) {
            String secret = newOtlpSecret();
            otlpReceiver = new io.graphrag.builder.capture.otlp.OtlpTraceReceiver();
            otlpReceiver.start("0.0.0.0", secret);
            otelEnv = otel.otlpEnv(config.sutId(), otlpReceiver.hostEndpoint(), secret);
            log.info("OTEL SQL capture (attach): otlp receiver {} (container reaches via {})",
                    otlpReceiver.endpoint(), otlpReceiver.hostEndpoint());
        } else if (sleuthMode) {
            // 0.0.0.0 바인드 → 컨테이너 SUT가 host.docker.internal:<port>로 Brave CLIENT span을 보고.
            // SQL 상관은 B3 헤더 주입으로(별도 OTEL agent 미사용), egress 발견은 이 Zipkin span으로.
            zipkinReceiver = new io.graphrag.builder.capture.zipkin.ZipkinSpanReceiver();
            zipkinReceiver.start("0.0.0.0");
            otelEnv = io.graphrag.builder.env.AnalysisEnvironment.sleuthZipkinEnv(zipkinReceiver.hostEndpoint());
            log.info("sleuth SQL+egress capture (attach): zipkin receiver {} (container reaches {}), "
                    + "B3 trace-id log correlation over services {}",
                    zipkinReceiver.endpoint(), zipkinReceiver.hostEndpoint(), effectiveCaptureServices(at));
        } else {
            otelEnv = otel.env(config.sutId());   // none: 기존 log 동작(OTEL env 동등, exporter none + baggage)
        }

        // 외부 HTTP 캡처(모든 attach 모드): 호스트 WireMock을 per-run token으로 띄우고, SUT env의
        // {{wiremock}}을 컨테이너가 도달 가능한 host.docker.internal:<port>[/token]로 치환한다.
        String httpToken = newOtlpSecret();   // reuse the per-run secret generator
        io.graphrag.builder.env.HttpCaptureServer httpCapture = new io.graphrag.builder.env.HttpCaptureServer(
                io.graphrag.builder.env.TraceKey.forMode(config.traceMode()));

        // httpCapture.start()를 포함한 모든 작업(WireMock 기동, override 생성/쓰기, envCfg, env 생성/start/explore)을
        // 같은 try로 감싼다 → start() 시점 실패(포트 바인드/스텁 로드)나 env로 소유권을 넘기기 전 throw에도
        // finally가 httpCapture/otlpReceiver를 정확히 1회 정리한다(성공적으로 넘긴 뒤에는 env의 try-with-resources가 소유).
        boolean handedOff = false;
        try {
            httpCapture.start(config.externalStubsDir(), httpToken);
            java.util.Map<String, String> mergedEnv = new java.util.LinkedHashMap<>(otelEnv);
            config.sutEnv().forEach((k, v) -> mergedEnv.put(k,
                    v.replace(io.graphrag.builder.env.AnalysisEnvironment.WIREMOCK_PLACEHOLDER, httpCapture.hostBaseUrl())));
            log.info("attach external HTTP capture: wiremock {} (container reaches host.docker.internal:{})",
                    httpCapture.baseUrl(), httpCapture.port());

            // host-gateway 는 외부 HTTP 캡처 때문에 항상 주입(true). sleuth 모드는 보조 capture-service에
            // 로깅/인코딩을 주입하도록 effectiveCaptureServices(at)를 extraLogServices로 전달(app 노드는 generator가 skip).
            List<String> extraVolumes =
                    List.of(workDir.resolve("pjacoco-exec").toAbsolutePath() + ":/grb-pjacoco-exec");
            String overrideYaml = new OverrideComposeGenerator().generate(
                    new OverrideComposeGenerator.Spec(at.appService(), agentsDir.toAbsolutePath().toString(),
                            at.appContainerPort(), at.appHostPort(), coverageContainerPort, at.coverageHostPort(),
                            jto, mybatisLogLevels, mergedEnv, true, otelSqlCapture,
                            effectiveCaptureServices(at), extraVolumes));
            Path overridePath = workDir.resolve("attach-override.yml");
            Files.writeString(overridePath, overrideYaml);

            var envCfg = new AttachedComposeEnvironment.Config(at.userCompose(), overridePath,
                    at.appService(), "grb-attach-" + config.sutId(),
                    "http://localhost:" + at.appHostPort(),
                    at.jdbcUrl(), config.dbConfig().user(), config.dbConfig().password(),
                    "localhost", at.coverageHostPort(), at.kafkaBootstrap(),
                    at.healthPath(), at.readyTimeoutSeconds(),
                    effectiveCaptureServices(at));   // app 포함 목록(Config도 빈 목록은 [app]로 정규화)

            AttachedComposeEnvironment env = new AttachedComposeEnvironment(
                    envCfg, config.dbConfig().type(), otlpReceiver, httpCapture, zipkinReceiver);
            handedOff = true;
            try (env) {
                env.start(workDir);
                // P2-4: attach 경로에도 execAwaitMs 적용
                long attachExecAwaitMs = config.execAwaitMs() > 0 ? config.execAwaitMs() : 30_000L;
                CoverageProbe attachProbe = new PjacocoCoverageProbe(new PjacocoCoverageBackend(
                        "localhost", at.coverageHostPort(),
                        workDir.resolve("pjacoco-exec"), attachExecAwaitMs));
                try {
                    return explore(env, config, index, wsIndex, kafkaIndex, mappers,
                            responseDtoFieldSets, plan, enumConstants, callSites, stringLiteralsByDto, acc, attachProbe);
                } finally {
                    attachProbe.shutdown();
                }
            }
        } finally {
            if (!handedOff) {
                httpCapture.close();
                if (otlpReceiver != null) { otlpReceiver.stop(); }
                if (zipkinReceiver != null) { zipkinReceiver.stop(); }
            }
        }
    }

    /** capture-services에 app 서비스를 반드시 포함(누락 시 prepend; 미지정 시 [app]). */
    private static List<String> effectiveCaptureServices(AttachConfig at) {
        List<String> req = at.captureServices();
        if (req.isEmpty()) {
            return List.of(at.appService());
        }
        if (req.contains(at.appService())) {
            return req;
        }
        List<String> out = new java.util.ArrayList<>();
        out.add(at.appService());
        out.addAll(req);
        return out;
    }

    /** OS가 임시 할당한 사용 가능한 포트를 반환한다 (ServerSocket(0) 패턴). */
    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to allocate free port", e);
        }
    }

    /** attach OTLP 리시버용 per-run 256-bit shared secret (hex). */
    private static String newOtlpSecret() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    /** host.docker.internal:host-gateway 는 Docker 20.10+ 필요. 미만이면 경고(best-effort 감지). */
    private static void warnIfHostGatewayUnsupported() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            p.waitFor();
            String[] parts = v.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].replaceAll("\\D.*$", ""));
                if (major < 20 || (major == 20 && minor < 10)) {
                    log.warn("Docker {} < 20.10: host.docker.internal:host-gateway may be unsupported; "
                            + "attach OTEL capture could fail to reach the host receiver", v);
                }
            }
        } catch (Exception e) {
            log.debug("docker version check skipped: {}", e.toString());
        }
    }

    private record ExplorationResult(int totalAppBranches, java.util.List<io.graphrag.model.TableSchema> tables) {}

    /**
     * Phase 0.5 spike: 락 제거 버전 — unlocked speedup 측정.
     * dump(reset=true) 동시 호출 시 IOException 발생 가능 → catch-and-return-empty로 무시.
     * 커버리지 수치는 완전히 무효(벽시계만 측정).
     */

    /** SUT 환경(env) 위에서 Kafka/HTTP/WS 탐색을 돌리고 acc를 채운다. analysis/attach 공통. */
    private static ExplorationResult explore(ExplorationEnvironment env, BuildConfig config,
                                             IndexResult index,
                                             io.graphrag.builder.index.WsIndexResult wsIndex,
                                             io.graphrag.builder.index.KafkaIndexResult kafkaIndex,
                                             List<MapperStatement> mappers,
                                             List<Set<String>> responseDtoFieldSets,
                                             IncrementalPlan plan,
                                             Map<String, List<String>> enumConstants,
                                             List<io.graphrag.builder.index.ExternalCallSite> callSites,
                                             Map<String, Map<String, List<String>>> stringLiteralsByDto,
                                             ExplorationAccumulators acc,
                                             CoverageProbe coverageProbe) throws Exception {
        List<ExploredPath> paths = acc.paths();
        List<CapturedSql> sql = acc.sql();
        List<CapturedHttpCall> httpCalls = acc.httpCalls();
        List<io.graphrag.model.WsExchange> wsExchanges = acc.wsExchanges();
        List<io.graphrag.model.KafkaExchange> kafkaExchanges = acc.kafkaExchanges();
        List<RequiredSeed> allSeeds = acc.allSeeds();
        List<ExplorationReport.EndpointExploration> reportEntries = acc.reportEntries();
        Set<io.graphrag.model.BranchRef> coveredAppBranches = acc.coveredAppBranches();
        org.jacoco.core.data.ExecutionDataStore runWideExec = acc.runWideExec();
        List<io.graphrag.model.CapturedEventEmit> capturedEventEmits = acc.capturedEventEmits();
        List<ExplorationReport.UnsupportedShape> unsupportedShapes = acc.unsupportedShapes();
        int totalAppBranches;
        List<TableSchema> tables;

        AuthTokenProvider authProvider = config.authConfig() == null ? null
                : new AuthTokenProvider(env.sut().baseUri(), config.authConfig(), config.requestHeaders());

        try (Connection connection = env.openConnection()) {
            tables = new io.graphrag.builder.schema.SchemaExtractor().extract(connection);
            log.info("extracted schema: {} table(s)", tables.size());

            CoverageProbe coverageClient = coverageProbe;
            BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer(config.sutJar());
            // BOOT-INF/classes 전체 분기 = app 커버리지 분모 (1회 산출)
            totalAppBranches = analyzer.analyze(
                    new org.jacoco.core.data.ExecutionDataStore()).totalBranches();
            ConstraintExtractor constraintExtractor = new ConstraintExtractor();
            LiteralCandidateExtractor literalExtractor = new LiteralCandidateExtractor();
            // R5: Spoon 모델을 스레드당 1회만 빌드해 그 스레드의 전 추출 호출에 재사용한다(이전엔 추출기 호출마다
            // SharedSpoonModel.build → 핸들러 수 비례 O(E)). 순차(P=1, 기본)에선 메인 스레드 모델 1개로
            // whole-app + 전 엔드포인트 = O(1). 병렬 fan-out(P>1)에선 워커 스레드별 독립 모델 → Spoon의
            // lazy 참조 해소(read 중 상태 변형)가 스레드 비안전이므로 모델을 공유하지 않고 스레드 격리한다.
            long spoonBuildsBefore = SharedSpoonModel.buildCount();
            ThreadLocal<spoon.reflect.CtModel> sharedModel =
                    ThreadLocal.withInitial(() -> SharedSpoonModel.build(config.sourceRoots()));
            // 비교식(분기 조건)은 전 계층 1회 추출 — rec-1(solverRelevantMissed) 라인 매칭용.
            List<ConstraintExtractor.Comparison> allComparisons =
                    constraintExtractor.extractComparisons(sharedModel.get());
            // 메서드 내 && conjunction(다필드 동시 가드) — joint 입력 합성 근거. 전 계층 1회.
            List<ConstraintExtractor.Conjunction> allConjunctions =
                    constraintExtractor.extractConjunctions(sharedModel.get());
            // 양변 모두 필드 참조인 비교 가드(REQ-006, REQ-008a) — joinGuards 변이 합성 근거. 전 계층 1회.
            List<ConstraintExtractor.JoinGuard> allJoinGuards =
                    constraintExtractor.extractJoinGuards(sharedModel.get());
            // 가드에서 직접 유래한 컬럼→유효 enum 상수 (시드 행 읽기 500 방지, Bug 3).
            Map<String, List<String>> enumColumns =
                    constraintExtractor.extractEnumColumns(sharedModel.get());
            // 상태 의존 가드(TEMPORAL/ENUM) + conjunction(저장행 복합 AND) ablation 게이트.
            // GRB_STATE_GUARDS=off 면 둘 다 빈 리스트 → 변종 pass 완전 no-op(ablation/회귀 control, REQ-008).
            boolean stateGuardsEnabled = stateGuardsEnabled(System.getenv("GRB_STATE_GUARDS"));
            Set<Map.Entry<String, String>> synthesisExcludeMethods = SynthesisMethodFilter.fromEnvironment();
            if (!synthesisExcludeMethods.isEmpty()) {
                log.info("input synthesis exclude: {} method(s) via {}", synthesisExcludeMethods.size(),
                        SynthesisMethodFilter.ENV);
            }
            // 상태 의존 가드(TEMPORAL/ENUM) — by-id 양 arm 시드 변종 근거 (Stage 4). 전 계층 1회.
            List<ConstraintExtractor.StateGuard> allStateGuards =
                    stateGuardsEnabled ? constraintExtractor.extractStateGuards(sharedModel.get()) : List.of();
            // 저장행 복합 AND 조건(conjunction) — 동시 만족 시드 변종 근거 (REQ-006). 전 계층 1회.
            // 입력-필드 allConjunctions와 구분(이쪽은 StateGuardConjunction).
            List<ConstraintExtractor.StateGuardConjunction> allStateGuardConjunctions =
                    stateGuardsEnabled ? constraintExtractor.extractStateGuardConjunctions(sharedModel.get()) : List.of();
            // 입력 후보 = 교체가능 오라클들의 합집합 (정적 리터럴 + ASM+Z3 concolic).
            // GRB_ORACLE=static 이면 concolic 제외 (오라클 기여도 ablation 측정용).
            io.graphrag.builder.oracle.InputOracle.SutCode sutCode =
                    new io.graphrag.builder.oracle.InputOracle.SutCode(config.sourceRoots(), config.sutJar());
            boolean useConcolic = !"static".equalsIgnoreCase(System.getenv("GRB_ORACLE"));
            io.graphrag.builder.oracle.InputCandidates inputCandidates =
                    new io.graphrag.builder.oracle.StaticLiteralOracle(sharedModel.get()).analyze(sutCode);
            if (useConcolic) {
                inputCandidates = inputCandidates.merge(
                        new io.graphrag.builder.oracle.ConcolicOracle().analyze(sutCode));
            }
            // LLM 값 오라클 — --llm-oracle 플래그 뒤에서만 union에 추가(비용 opt-in). 캐시 우선,
            // 키 없고 캐시 miss면 내부에서 skip(CI 오프라인). off면 완전 no-op(회귀 0).
            if (config.llm().enabled()) {
                io.graphrag.builder.oracle.LlmOptions llmOpts = config.llm();
                io.graphrag.builder.oracle.LlmBackends.Selection sel =
                        io.graphrag.builder.oracle.LlmBackends.create(
                                llmOpts.backend(), llmOpts.model(), llmOpts.cli());
                io.graphrag.builder.oracle.LlmOracle llm = new io.graphrag.builder.oracle.LlmOracle(
                        index, new io.graphrag.builder.index.ValidationConstraintExtractor(),
                        new io.graphrag.builder.oracle.HandlerSourceExtractor(sharedModel.get()),
                        sel.client(), io.graphrag.builder.oracle.LlmValueCache.defaultClasspath(),
                        llmOpts.model(), sel.usable(), sharedModel.get());
                inputCandidates = inputCandidates.merge(llm.analyze(sutCode));
                log.info("llm oracle merged (backend={}, model={}, usable={})",
                        llmOpts.backend(), llmOpts.model(), sel.usable());
            }
            log.info("input oracles (concolic={}, llm={}) → {} numeric field(s), {} string field(s)",
                    useConcolic, config.llm().enabled(),
                    inputCandidates.numeric().size(), inputCandidates.strings().size());

            // @KafkaListener consumer: HTTP 탐색보다 먼저 실행(#3 순서 불변식). consumer가 쓴
            // 행을 read 엔드포인트가 관측(read 보너스)하고, consumer 자신의 실행 커버리지(delta)도
            // runWideExec에 병합돼 exploration 지표에 반영된다. baseline dump가 boot 구간을
            // 잘라내므로 뒤따르는 HTTP baseline에 새는 것 없음.
            // SQL 캡처 backend는 env.start() 이후(sut() non-null)에 구성한다. OTEL 모드면 Environment가
            // 소유한 OTLP receiver로 span 캡처(요청별 traceparent로 귀속), 아니면 로그 파싱 폴백(기존 동작 동일).
            // Kafka(레코드 헤더 주입)와 HTTP(요청 헤더 주입) 경로가 동일 backend를 공유 — 단조 traceId 카운터로
            // 모든 발행/요청이 고유 trace를 받는다. Kafka 블록보다 먼저 만들어 runner에 주입한다.
            // 결정적 시드: 같은 commit 재분석은 동일 trace 시퀀스(재현성), 다른 SUT/commit은 충돌 없음.
            String traceRunId = config.commitSha() == null
                    ? config.sutId() : config.sutId() + ":" + config.commitSha();
            // 전체 빌드 런에서 유일한 traceId 생성기: 동시 runner들이 같은 인스턴스를 공유해
            // Phase 2 병렬 실행에서도 traceId 충돌이 없다 (runId-seed + AtomicLong 카운터).
            TraceParent sharedTraceParent = new TraceParent(traceRunId);
            String mode = config.traceMode();
            // F1(REQ-P009): parallelism>1이면 OtelSpanCapture를 parallelAware 모드로 생성.
            // 이 모드에서는 log-parser 폴백이 비활성화된다 — 폴백은 timestamp-window 기반으로
            // 동시 워커 로그가 섞이면 SQL 교차 오염이 발생하기 때문이다 (P2-5 F1).
            // F1b(REQ-P009): 병렬 모드에서 OTLP BSP export 지연 증가 → awaitTimeout 상향.
            // sqlAwaitMs=0이면 모드별 기본값(순차 8_000ms, 병렬 30_000ms) 적용.
            boolean parallelAware = config.parallelism() > 1;
            io.graphrag.builder.capture.SqlCaptureBackend sqlCapture;
            if ("otel".equals(mode) && env.otlpReceiver() != null) {
                // attach: 컨테이너→호스트 OTLP가 Docker Desktop VM hop을 거쳐 span 도착 jitter가 크므로
                // quiescence 창을 넓힌다(빠른 요청의 db span 누락→log-parser 폴백 race 완화). analysis는 기본.
                long quiescenceMs = config.attach() != null
                        ? io.graphrag.builder.capture.OtelSpanCapture.ATTACH_QUIESCENCE_MILLIS : 0L;
                sqlCapture = new io.graphrag.builder.capture.OtelSpanCapture(env.otlpReceiver(), env.sut(),
                        new io.graphrag.builder.capture.TraceParent(traceRunId), parallelAware,
                        config.sqlAwaitMs(), quiescenceMs);
            } else if ("sleuth".equals(mode)) {
                // per-run nonce(R5): 동일 commit 동시 실행 시 trace 시퀀스 충돌 방지(SecureRandom, 비결정적 OK).
                sqlCapture = new io.graphrag.builder.capture.SleuthLogCapture(env.sut(),
                        new io.graphrag.builder.capture.B3TraceId(traceRunId, newOtlpSecret()));
            } else {
                sqlCapture = new io.graphrag.builder.capture.LogParserCapture(env.sut());
            }

            // @KafkaListener consumer: HTTP 탐색보다 먼저 실행(#3 순서 불변식). consumer가 쓴
            // 행을 read 엔드포인트가 관측(read 보너스)하고, consumer 자신의 실행 커버리지(delta)도
            // runWideExec에 병합돼 exploration 지표에 반영된다. baseline dump가 boot 구간을
            // 잘라내므로 뒤따르는 HTTP baseline에 새는 것 없음.
            String kafkaBootstrap = env.kafkaBootstrapServers();
            if (kafkaBootstrap == null && !kafkaIndex.consumers().isEmpty()) {
                log.warn("{} kafka consumer(s) skipped (no kafka bootstrap configured)",
                        kafkaIndex.consumers().size());
            }
            if (kafkaBootstrap != null && !kafkaIndex.consumers().isEmpty()) {
                io.graphrag.builder.run.KafkaCaptureRunner kafkaRunner =
                        new io.graphrag.builder.run.KafkaCaptureRunner(
                                connection, env.dbType(), kafkaBootstrap, coverageClient, sqlCapture,
                                sharedTraceParent);
                for (io.graphrag.model.KafkaConsumer kafkaConsumer : kafkaIndex.consumers()) {
                    if (!plan.shouldExplore(kafkaConsumer.id())) {
                        continue;
                    }
                    BodyShape kShape = kafkaIndex.payloadShapes().get(kafkaConsumer.payloadType());
                    io.graphrag.builder.run.KafkaCaptureRunner.KafkaResult kResult =
                            kafkaRunner.run(kafkaConsumer, kShape, tables);
                    kafkaExchanges.addAll(kResult.exchanges());
                    sql.addAll(kResult.sql());
                    kResult.cumulativeExec().accept(runWideExec);   // consumer 커버 병합
                }
            }

            io.graphrag.builder.run.KafkaCaptureReceiver kafkaCapture = null;
            if (config.withKafka() && kafkaBootstrap != null) {
                kafkaCapture = new io.graphrag.builder.run.KafkaCaptureReceiver(kafkaBootstrap);
                kafkaCapture.start();
                log.info("KafkaCaptureReceiver started for outbound event capture on {}", kafkaBootstrap);
            }

            // REQ-012: 고유 핸들러당 Spoon 1회만 호출(캐시). 키 = "classFqn#method".
            Map<String, Set<Map.Entry<String, String>>> reachableCache = new HashMap<>();

            try (io.graphrag.builder.run.KafkaCaptureReceiver receiverToClose = kafkaCapture) {
                // Phase 2: parallelism=1이면 순차 경로, N>1이면 ExecutorService fan-out.
                // 각 워커는 자기 Connection(workerConn) + 로컬 누적기를 가지고 EndpointResult를 반환.
                // 전 워커 완료 후 단일 스레드에서 merge (REQ-P005, REQ-P006).
                int parallelism = config.parallelism();
                log.info("HTTP endpoint loop parallelism={}", parallelism);

                // 탐색 대상 엔드포인트만 사전 필터(순차와 동일한 skip 로직)
                List<Endpoint> toExplore = filterEndpoints(index, plan, config, unsupportedShapes);
                log.info("endpoints to explore: {} (parallelism={})", toExplore.size(), parallelism);

                // 실제 탐색 실행 — 순차(P=1) 또는 병렬(P>1)
                // captureCtx: 워커 내부에서 shape 재도출 필요 → index/config를 클로저로 캡처
                final List<ConstraintExtractor.Comparison> sharedAllComparisons = allComparisons;
                final List<ConstraintExtractor.Conjunction> sharedAllConjunctions = allConjunctions;
                final List<ConstraintExtractor.JoinGuard> sharedAllJoinGuards = allJoinGuards;
                final List<ConstraintExtractor.StateGuard> sharedAllStateGuards = allStateGuards;
                final io.graphrag.builder.oracle.InputCandidates sharedInputCandidates = inputCandidates;
                final Map<String, List<String>> sharedEnumColumns = enumColumns;
                final ConstraintExtractor sharedConstraintExtractor = constraintExtractor;
                final LiteralCandidateExtractor sharedLiteralExtractor = literalExtractor;
                final AuthTokenProvider sharedAuthProvider = authProvider;
                final io.graphrag.builder.capture.SqlCaptureBackend sharedSqlCapture = sqlCapture;
                final TraceParent sharedTraceParentRef = sharedTraceParent;
                final List<Set<String>> sharedResponseDtoFieldSets = responseDtoFieldSets;
                final BranchCoverageAnalyzer sharedAnalyzer = analyzer;
                final CoverageProbe sharedCoverageClient = coverageProbe;
                final List<ConstraintExtractor.StateGuardConjunction> sharedAllStateGuardConjunctions = allStateGuardConjunctions;
                // REQ-012: handler→reachable 캐시. 병렬 워커가 computeIfAbsent로 공유 → ConcurrentHashMap.
                final Map<String, Set<Map.Entry<String, String>>> sharedReachableCache =
                        new java.util.concurrent.ConcurrentHashMap<>();

                // workerTask: 엔드포인트 1개를 자기 Connection으로 탐색해 EndpointResult 반환
                java.util.function.Function<Endpoint, EndpointExplorationRunner.EndpointResult> workerTask =
                        endpoint -> {
                    try (Connection workerConn = env.openConnection()) {
                        BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
                        String bodyFqn2 = endpoint.params().stream()
                                .filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY
                                        || p.kind() == io.graphrag.model.ParamKind.FORM)
                                .map(io.graphrag.model.EndpointParam::javaType)
                                .findFirst().orElse(null);
                        if (shape == null && bodyFqn2 != null && config.reflectInstantiate()) {
                            var reflected = new ReflectiveBodyInstantiator(config.reflectInstantiate())
                                    .resolve(bodyFqn2, config.sutJar());
                            if (reflected.isPresent()) {
                                shape = reflected.get().shape();
                            }
                        }

                        // R5: 이 워커 스레드의 모델(ThreadLocal)을 재사용 — 스레드당 1회 빌드. 병렬에서도
                        // 스레드 격리라 Spoon lazy-resolve race 없음.
                        spoon.reflect.CtModel workerSpoon = sharedModel.get();
                        var conditions = sharedConstraintExtractor.extract(
                                workerSpoon, endpoint.handlerClass(), endpoint.handlerMethod());
                        var literals = sharedLiteralExtractor.extract(workerSpoon, endpoint.handlerClass());
                        Map<String, List<ValidationConstraintExtractor.FieldConstraint>> fieldConstraints =
                                shape == null ? Map.of()
                                        : new ValidationConstraintExtractor()
                                                .extract(workerSpoon, shape.javaType());

                        // 공유 CoverageProbe를 직접 사용한다 — pjacoco는 traceId별 격리로 concurrent-safe.
                        EndpointExplorationRunner runner = new EndpointExplorationRunner(
                                env.sut(), workerConn, env.dbType(),
                                sharedCoverageClient, sharedAnalyzer,
                                config.budgetRequests(), env.httpCapture(),
                                sharedResponseDtoFieldSets, literals,
                                sharedAuthProvider, config.authConfig(), enumConstants, sharedEnumColumns,
                                config.requestHeaders(), sharedSqlCapture, receiverToClose,
                                config.classifierConfig().toClassifier(), callSites,
                                io.graphrag.builder.capture.egress.EgressCollector.forMode(env), stringLiteralsByDto,
                                sharedTraceParentRef,
                                io.graphrag.builder.run.ErrorContractDescriptor.fromClassifierConfig(config.classifierConfig()));

                        // REQ-012: handler당 reachable 집합(cross-class 귀속). 병렬 워커 공유 → ConcurrentHashMap.
                        // computeIfAbsent는 키별 1회 실행(실행 스레드의 workerSpoon 사용) — Spoon 재빌드 없이 traversal만.
                        String handlerKey = endpoint.handlerClass() + "#" + endpoint.handlerMethod();
                        Set<Map.Entry<String, String>> reachable = sharedReachableCache.computeIfAbsent(
                                handlerKey,
                                k -> sharedConstraintExtractor.reachableMethods(
                                        workerSpoon, endpoint.handlerClass(), endpoint.handlerMethod()));
                        List<ConstraintExtractor.StateGuard> endpointStateGuards = sharedAllStateGuards.stream()
                                .filter(g -> isReachable(reachable, g.classFqn(), g.method()))
                                .filter(g -> !SynthesisMethodFilter.matches(synthesisExcludeMethods,
                                        g.classFqn(), g.method()))
                                .toList();
                        List<ConstraintExtractor.JoinGuard> endpointJoinGuards = sharedAllJoinGuards.stream()
                                .filter(g -> isReachable(reachable, g.classFqn(), g.method()))
                                .filter(g -> !SynthesisMethodFilter.matches(synthesisExcludeMethods,
                                        g.classFqn(), g.method()))
                                .toList();
                        // REQ-006: 이 엔드포인트 reachable에 귀속된 conjunction만 전달(cross-class 포함).
                        List<ConstraintExtractor.StateGuardConjunction> endpointStateGuardConjunctions =
                                sharedAllStateGuardConjunctions.stream()
                                        .filter(c -> isReachable(reachable, c.classFqn(), c.method()))
                                        .filter(c -> !SynthesisMethodFilter.matches(synthesisExcludeMethods,
                                                c.classFqn(), c.method()))
                                        .toList();
                        boolean skipHappySynthesis = SynthesisMethodFilter.reachableTouchesExcluded(
                                reachable, synthesisExcludeMethods);
                        if (skipHappySynthesis) {
                            log.info("skip happy input synthesis for {} ({})", endpoint.id(),
                                    SynthesisMethodFilter.ENV);
                        }

                        return runner.run(endpoint, shape, tables, conditions,
                                sharedAllComparisons, sharedInputCandidates, fieldConstraints,
                                sharedAllConjunctions, endpointJoinGuards, endpointStateGuards,
                                endpointStateGuardConjunctions,
                                index.validBodyEndpointIds().contains(endpoint.id()),
                                index.bodyShapes(),
                                index.formBindingIndex().getOrDefault(endpoint.id(), List.of()),
                                skipHappySynthesis);
                    } catch (Exception e) {
                        throw new RuntimeException("endpoint exploration failed for " + endpoint.id(), e);
                    }
                };

                // 탐색 실행 및 결과 수집
                List<EndpointExplorationRunner.EndpointResult> endpointResults;
                if (parallelism == 1) {
                    // 순차 경로: 기존 동작과 동일
                    endpointResults = new ArrayList<>();
                    for (Endpoint endpoint : toExplore) {
                        endpointResults.add(workerTask.apply(endpoint));
                    }
                } else {
                    // 병렬 경로: ExecutorService fan-out
                    ExecutorService pool = Executors.newFixedThreadPool(
                            Math.min(parallelism, toExplore.size() == 0 ? 1 : toExplore.size()));
                    try {
                        List<Future<EndpointExplorationRunner.EndpointResult>> futures = new ArrayList<>();
                        for (Endpoint endpoint : toExplore) {
                            futures.add(pool.submit(() -> workerTask.apply(endpoint)));
                        }
                        endpointResults = new ArrayList<>(futures.size());
                        for (Future<EndpointExplorationRunner.EndpointResult> f : futures) {
                            endpointResults.add(f.get());   // 예외 전파: 워커 실패 시 빌드 실패
                        }
                    } finally {
                        pool.shutdown();
                    }
                }

                // 단일 스레드 merge: 순서·동시성 race 없음. toExplore와 결과는 동일 순서(병렬도 future 순서 보존).
                for (int ri = 0; ri < endpointResults.size(); ri++) {
                    Endpoint endpoint = toExplore.get(ri);
                    EndpointExplorationRunner.EndpointResult result = endpointResults.get(ri);
                    paths.addAll(result.paths());
                    sql.addAll(result.sql());
                    httpCalls.addAll(result.httpCalls());
                    allSeeds.addAll(result.seeds());
                    reportEntries.add(result.report());
                    capturedEventEmits.addAll(result.capturedEventEmits());
                    // REQ-010: 외부 stub loud-fail 4종을 빌드 리포트(unsupportedShapes 채널)에 기록.
                    for (EndpointExplorationRunner.LoudFail lf : result.externalLoudFails()) {
                        unsupportedShapes.add(new ExplorationReport.UnsupportedShape(
                                endpoint.id(), lf.target(), lf.reason()));
                    }
                    result.cumulativeExec().accept(runWideExec);   // OR 병합 (line 집계용)
                }
            }

            // R5: 탐색 정적 분석이 유발한 Spoon 빌드 수 = 스레드 수(순차=1). 핸들러 수와 무관(스레드당 1회 재사용).
            log.info("R5: explore static-analysis Spoon builds = {} (스레드당 1회; 핸들러 수 무관)",
                    SharedSpoonModel.buildCount() - spoonBuildsBefore);

            io.graphrag.builder.run.WsCaptureRunner wsRunner =
                    new io.graphrag.builder.run.WsCaptureRunner(
                            env.sut(), connection, env.dbType(), coverageClient);
            for (io.graphrag.model.WsEndpoint wsEndpoint : wsIndex.endpoints()) {
                if (!plan.shouldExplore(wsEndpoint.id())) {
                    log.info("skip {} (partition clean; carrying over)", wsEndpoint.id());
                    continue;
                }
                BodyShape shape = wsIndex.payloadShapes().get(wsEndpoint.payloadType());
                if (shape == null) {
                    log.warn("skip {} (no payload shape)", wsEndpoint.id());
                    continue;
                }
                io.graphrag.builder.run.WsCaptureRunner.WsResult result =
                        wsRunner.run(wsEndpoint, shape, tables);
                wsExchanges.addAll(result.exchanges());
                sql.addAll(result.sql());
                result.cumulativeExec().accept(runWideExec);   // WS 핸들러 커버 병합
            }

            // 전 루프(Kafka + HTTP + WS) 종료 후 1회 집계 — consumer/WS 커버까지 지표에 반영된다.
            var explCov = analyzer.analyze(runWideExec);
            coveredAppBranches.addAll(explCov.covered());
            log.info("exploration coverage [{}]: line {}/{} ({}%), branch {}/{} ({}%)",
                    config.sutId(), explCov.coveredLines(), explCov.totalLines(),
                    explCov.totalLines() == 0 ? 0 : 100 * explCov.coveredLines() / explCov.totalLines(),
                    explCov.covered().size(), explCov.totalBranches(),
                    explCov.totalBranches() == 0 ? 0 : 100 * explCov.covered().size() / explCov.totalBranches());
        }
        return new ExplorationResult(totalAppBranches, tables);
    }

    /** attach 모드 CLI 설정 (사용자 compose + 생성 override). */
    public record AttachConfig(Path userCompose, String appService,
                               int appContainerPort, int appHostPort, int coverageHostPort,
                               String jdbcUrl, String kafkaBootstrap,
                               String healthPath, int readyTimeoutSeconds,
                               java.util.List<String> captureServices) {}

    /** docs/22 Manual-Archive Seed: 수동 작성 ExploredPath 병합 (id 충돌 시 수동본 우선). */
    private static void mergeManualPaths(Path dir, List<ExploredPath> paths) throws Exception {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                ExploredPath manual = Json.mapper()
                        .readValue(Files.readString(file), ExploredPath.class);
                paths.removeIf(p -> p.id().equals(manual.id()));
                paths.add(manual);
                log.info("merged manual path: {} ({})", manual.id(), file.getFileName());
            }
        }
    }

    private static BodyShape bodyShapeFor(Endpoint endpoint, Map<String, BodyShape> shapes) {
        return endpoint.params().stream()
                .filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY
                        || p.kind() == io.graphrag.model.ParamKind.FORM)   // @Controller 폼 커맨드 객체도 body shape
                .map(p -> shapes.get(p.javaType()))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    static List<Endpoint> filterEndpoints(
            IndexResult index,
            IncrementalPlan plan,
            BuildConfig config,
            List<ExplorationReport.UnsupportedShape> unsupportedShapes) {
        boolean allowEmptyBody = "1".equals(System.getenv("GRB_EXPLORER_EMPTY_BODY"))
                || "1".equals(System.getProperty("GRB_EXPLORER_EMPTY_BODY"));
        ReflectiveBodyInstantiator instantiator = config.reflectInstantiate()
                ? new ReflectiveBodyInstantiator(true)
                : null;
        List<Endpoint> toExplore = new ArrayList<>();
        for (Endpoint endpoint : index.endpoints()) {
            if (!plan.shouldExplore(endpoint.id())) {
                log.info("skip {} (partition clean; carrying over)", endpoint.id());
                continue;
            }
            BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
            boolean hasPathParam = endpoint.params().stream()
                    .anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.PATH);
            String bodyFqn = endpoint.params().stream()
                    .filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY
                            || p.kind() == io.graphrag.model.ParamKind.FORM)
                    .map(io.graphrag.model.EndpointParam::javaType)
                    .findFirst().orElse(null);
            if (shape == null && bodyFqn != null) {
                if (instantiator != null) {
                    var reflected = instantiator.resolve(bodyFqn, config.sutJar());
                    if (reflected.isPresent()) {
                        shape = reflected.get().shape();
                        log.info("reflect-instantiate fallback: {} → {} field(s)",
                                bodyFqn, reflected.get().shape().fields().size());
                    } else {
                        unsupportedShapes.add(new ExplorationReport.UnsupportedShape(
                                endpoint.id(), bodyFqn, "reflect-instantiate failed"));
                    }
                } else {
                    unsupportedShapes.add(new ExplorationReport.UnsupportedShape(
                            endpoint.id(), bodyFqn, "reflect-instantiate disabled"));
                }
            }
            if (shape == null
                    && !endpoint.httpMethod().equals("GET") && !hasPathParam) {
                if (!allowEmptyBody) {
                    log.warn("skip {} (no @RequestBody shape and no path param)", endpoint.id());
                    continue;
                }
                log.info("empty-body explore {} (GRB_EXPLORER_EMPTY_BODY=1)", endpoint.id());
            }
            toExplore.add(endpoint);
        }
        return toExplore;
    }

    /** "K=V[,K2=V2]" 형식 파싱. */
    private static Map<String, String> parseEnvPairs(String spec) {
        Map<String, String> env = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return env;
        }
        for (String pair : spec.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("invalid --sut-env entry: " + pair);
            }
            env.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return env;
    }

    /** "a,b,c" → [a,b,c] (공백 strip, 빈 토큰 제거). null/빈 → 빈 리스트. (테스트용 package-private) */
    static List<String> parseCsv(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(spec.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** `coverage --exec <file> --jar <bootjar> [--label x]` — .exec를 라인+브랜치로 분석해 출력. */
    private static void runCoverageReport(Map<String, String> o) throws Exception {
        Path exec = Path.of(required(o, "--exec"));
        Path jar = Path.of(required(o, "--jar"));
        org.jacoco.core.tools.ExecFileLoader loader = new org.jacoco.core.tools.ExecFileLoader();
        loader.load(exec.toFile());
        var cov = new BranchCoverageAnalyzer(jar).analyze(loader.getExecutionDataStore());
        int cl = cov.coveredLines();
        int tl = cov.totalLines();
        int cb = cov.covered().size();
        int tb = cov.totalBranches();
        System.out.printf("COVERAGE %s: line %d/%d (%.0f%%), branch %d/%d (%.0f%%)%n",
                o.getOrDefault("--label", "-"),
                cl, tl, tl == 0 ? 0.0 : 100.0 * cl / tl,
                cb, tb, tb == 0 ? 0.0 : 100.0 * cb / tb);
        // 진단: 미커버 브랜치를 class#method → line 으로 그룹 출력 (도달 불가 사유 분석용).
        if (o.containsKey("--missed")) {
            java.util.Map<String, java.util.TreeMap<Integer, Long>> byMethod = new java.util.TreeMap<>();
            for (io.graphrag.model.BranchRef b : cov.missed()) {
                byMethod.computeIfAbsent(b.classFqn() + "#" + b.method(), k -> new java.util.TreeMap<>())
                        .merge(b.line(), 1L, Long::sum);
            }
            byMethod.forEach((cm, lines) -> {
                long miss = lines.values().stream().mapToLong(Long::longValue).sum();
                System.out.printf("  MISSED %s : %d branch(es) at lines %s%n", cm, miss, lines.keySet());
            });
        }
    }

    /**
     * provenance 서브커맨드: {@code --sut-src}/{@code --endpoint} 정적 인덱싱 경로(build()와 동일 배선)만
     * 재사용해 SUT 부팅 없이 단일 엔드포인트의 provenance 리포트를 산출한다. usage:
     * {@code provenance --sut-src <dir> --endpoint 'POST /api/transfers' [--provenance-depth 3] --out <file>}
     */
    private static void runProvenance(Map<String, String> o) throws Exception {
        SourceRoots sourceRoots = buildSourceRoots(o);
        String endpointSpec = required(o, "--endpoint");
        int maxDepth = Integer.parseInt(o.getOrDefault("--provenance-depth", "3"));
        Path outPath = Path.of(required(o, "--out"));

        Path sutResources = o.containsKey("--sut-resources") ? Path.of(o.get("--sut-resources")) : null;
        List<Path> resourceDirs = SutSrcResolver.resourceDirs(sourceRoots, sutResources);
        StaticIndexBundle bundle = indexStatically(sourceRoots, resourceDirs, null);
        IndexResult index = bundle.index();

        Set<String> matchedIds = EndpointSelector.resolve(List.of(endpointSpec),
                index.endpoints(), bundle.ws().endpoints(), bundle.kafka().consumers());
        List<Endpoint> matches = index.endpoints().stream()
                .filter(e -> matchedIds.contains(e.id()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "--endpoint '" + endpointSpec + "' must resolve to exactly one HTTP endpoint "
                            + "for provenance, got " + matches.size());
        }
        Endpoint endpoint = matches.get(0);

        Set<String> mapperFqns = bundle.mappers().stream()
                .map(MapperStatement::namespace)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        spoon.reflect.CtModel model = SharedSpoonModel.build(sourceRoots);
        ProvenanceReport report = new ProvenanceIndexer(mapperFqns).analyze(model, endpoint, maxDepth);

        Path parent = outPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outPath,
                Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        log.info("provenance report for {}: {} guard(s), {} unresolved(s) -> {}",
                endpoint.id(), report.guards().size(), report.unresolved().size(), outPath);
    }

    /**
     * synthesize-triple 서브커맨드: provenance 리포트(REQ-001 산출물)를 읽어 {@link TripleSynthesizer}로
     * 후보 트리플(body.json/seed.sql/stubs.json/notes.md)을 산출한다(REQ-005/007/008/033). usage:
     * {@code synthesize-triple --report <provenance-report.json> --triple-store <dir>}.
     *
     * <p>이 CLI는 provenance 리포트만으로 동작하는 최소 배선이다 — 물리 스키마(seed FK 부모 채움)나
     * body 형상 검증이 필요하면 상위 오케스트레이션(에이전트 스킬)이 그래프 자산에서 별도로 채워
     * {@link TripleSynthesizer#synthesize}를 직접 호출하는 경로를 쓸 수 있다(현재 CLI는 tables=[],
     * oracle=empty로 호출 — guard가 결정하는 값은 스키마 없이도 대부분 결정되고, 결정 불가한 자리는
     * 갭 마커로 표기되므로 안전하다).
     *
     * <p><b>base/ 사본(REQ-009 마커-diff용, T1):</b> {@code cand-NN}과 동일한 도구 생성 내용을
     * {@code base/cand-NN}에도 그대로 저장한다 — 에이전트가 {@code cand-NN}의 갭 마커를 채운 뒤,
     * {@link io.graphrag.builder.provenance.TripleValidator}가 이 미변경 사본과 diff해 마커 외
     * 변경을 reject한다(REQ-009). 저장 레이아웃의 완전한 정식화(promoted/failed 이동 등)는
     * Task 11({@code TripleStore})의 범위이며, 여기서는 base 보존만 최소 배선한다.
     */
    private static void runSynthesizeTriple(Map<String, String> o) throws Exception {
        Path reportPath = Path.of(required(o, "--report"));
        Path tripleStore = Path.of(required(o, "--triple-store"));

        ProvenanceReport report = Json.mapper().readValue(reportPath.toFile(), ProvenanceReport.class);
        List<TripleCandidate> candidates = new TripleSynthesizer().synthesize(
                report, BodyShape.empty(), List.of(), io.graphrag.builder.oracle.InputCandidates.empty());

        Path endpointDir = tripleStore.resolve(report.endpointId());
        for (int i = 0; i < candidates.size(); i++) {
            TripleCandidate candidate = candidates.get(i);
            String candName = String.format("cand-%02d", i + 1);
            Path candDir = Files.createDirectories(endpointDir.resolve(candName));
            Path baseDir = Files.createDirectories(endpointDir.resolve("base").resolve(candName));
            String bodyJson = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(candidate.body());
            String seedSql = String.join(System.lineSeparator(), candidate.seedSqlStatements());
            String stubsJson = stubsJsonContent(candidate.stubMappings());
            for (Path dir : List.of(candDir, baseDir)) {
                Files.writeString(dir.resolve("body.json"), bodyJson);
                Files.writeString(dir.resolve("seed.sql"), seedSql);
                Files.writeString(dir.resolve("stubs.json"), stubsJson);
            }
            Files.writeString(candDir.resolve("notes.md"), candidate.notes());
        }
        log.info("synthesize-triple: {} candidate(s) for {} -> {}",
                candidates.size(), report.endpointId(), endpointDir);
    }

    /**
     * stubs.json 내용: WireMock {@code StubMapping.buildFrom}은 파일당 단일 mapping 객체를 기대한다
     * (기존 {@code HttpCaptureServer.loadStubs} 규약, REQ-008). 후보에 stub이 없으면 빈 객체, 정확히
     * 1개면 그 mapping 그대로, 2개 이상(현재 라우팅에서는 발생하지 않지만 방어적으로)이면 첫 번째만
     * 채택하고 나머지는 notes.md의 trace로만 남는다(단일-파일-단일-mapping 규약 유지).
     */
    private static String stubsJsonContent(List<com.fasterxml.jackson.databind.node.ObjectNode> stubMappings)
            throws Exception {
        Object toWrite = stubMappings.isEmpty() ? Json.mapper().createObjectNode() : stubMappings.get(0);
        return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(toWrite);
    }

    /**
     * trial 서브커맨드(T2, REQ-013/014/016): {@code --triple-store}(또는 후보 전용
     * {@code --triple-candidates})의 대기 후보를 순번순으로 시도한다. 각 시도는
     * {@link io.graphrag.builder.provenance.TrialRunner}의 4단계 시퀀스(happy 시드 정리→후보
     * seed.sql INSERT→stub 등록→invoke)로 적용되고, 판정이 성공이면 그 후보를 {@code promoted/}로
     * 옮기고 즉시 0을 반환한다. {@code --trial-budget}(기본 8)회 시도 안에 성공이 없으면(후보
     * 소진 포함) **시도한** 후보만 {@code failed/}로 옮기고 endpoint 디렉토리에 최종 다이제스트
     * 보고서({@code failed/digest-final.json})를 남긴 뒤 3을 반환한다.
     *
     * <p><b>예산 소진 semantics(REQ-016):</b> budget이 대기 후보 수보다 적어 일부를 시도조차 못 하면,
     * <b>미시도 후보는 원래 위치(top-level {@code cand-NN})에 그대로 남는다</b> — 다음 trial 실행에서
     * 예산이 재충전된 채로 이어서 시도할 수 있게 하기 위함(promoted/failed 어느 쪽으로도 이동하지
     * 않고, {@code digest-final.json}에도 포함되지 않는다 — 그 보고서는 "이번 실행에서 실제로 시도한
     * 것"만의 기록이다). 이는 {@link TripleStore#candidates}가 사전에 스냅샷한 목록을 순회하다
     * budget 도달 시 단순히 {@code break}하는 구현의 직접적 귀결이며, 의도된 계약이다(재시도를 위해
     * 후보를 보존 — 실패로 낙인찍지 않음).
     *
     * <p>usage: {@code trial --endpoint <id> --http-method <M> --path </x> --sut-base-url <url>
     * --jdbc-url <url> --db-user <u> --db-password <p> --db-type postgres|mysql|mariadb
     * [--triple-store <dir>] [--triple-candidates <dir>] [--trial-budget 8]
     * [--happy-seeds <required-seeds.json>] [--provenance-report <report.json>]
     * [--sut-log-file <path>] [--error-when-present ...]}.
     *
     * <p><b>REQ-036 부분 배선(각주):</b> {@code --triple-store}/{@code --triple-candidates}의 기본
     * 경로({@code .graphrag/triples})와 두 플래그의 분리(생성 루트 vs trial이 읽는 후보 디렉토리)만
     * 이 task에서 배선한다. 미배선(향후 task 소관): (a) SUT가 실제로 쓰는 외부 stub WireMock에
     * attach하는 방법 — 이 CLI는 자체 {@code HttpCaptureServer}를 기동하지 않으므로 stub 등록(③)은
     * 항상 skip된다, (b) {@code --graph}로 그래프 자산에서 Endpoint/happy 시드를 자동 로드하는 경로
     * (REQ-018 T3 파이프라인 통합 소관) — 이 CLI는 {@code --http-method}/{@code --path}로 Endpoint를
     * 직접 명시받고, happy 시드는 별도 JSON 파일로 받는다. e2e fixture {@code promoted/} 커밋 경로는
     * Task 18 소관.
     */
    static int runTrial(Map<String, String> o) throws Exception {
        Path tripleStoreRoot = Path.of(o.getOrDefault("--triple-store", ".graphrag/triples"));
        Path candidatesRoot = o.containsKey("--triple-candidates")
                ? Path.of(o.get("--triple-candidates")) : tripleStoreRoot;
        String endpointId = required(o, "--endpoint");
        Endpoint endpoint = new Endpoint(
                endpointId, required(o, "--http-method"), required(o, "--path"),
                "unknown", "unknown", List.of(), false);
        int budget = Integer.parseInt(o.getOrDefault("--trial-budget", "8"));

        List<RequiredSeed> happySeeds = o.containsKey("--happy-seeds")
                ? List.of(Json.mapper().readValue(Path.of(o.get("--happy-seeds")).toFile(), RequiredSeed[].class))
                : List.of();
        ProvenanceReport report = o.containsKey("--provenance-report")
                ? Json.mapper().readValue(Path.of(o.get("--provenance-report")).toFile(), ProvenanceReport.class)
                : null;

        DbConfig.Type dbType = DbConfig.Type.valueOf(
                required(o, "--db-type").toUpperCase(java.util.Locale.ROOT));
        ResponseClassifier classifier = ClassifierConfig.from(o).toClassifier();

        try (Connection connection = java.sql.DriverManager.getConnection(
                required(o, "--jdbc-url"), o.getOrDefault("--db-user", ""), o.getOrDefault("--db-password", ""))) {
            SutHandle sut = new LogFileSutHandle(
                    required(o, "--sut-base-url"),
                    o.containsKey("--sut-log-file") ? Path.of(o.get("--sut-log-file")) : null);
            EndpointExplorationRunner invokeRunner = new EndpointExplorationRunner(
                    sut, connection, dbType, null, null, 0, null,
                    List.of(), List.of(), null, null, Map.of(), Map.of(),
                    RequestHeaders.empty(), null, null);
            TrialRunner trialRunner = new TrialRunner(
                    connection, dbType, null, classifier, sut, invokeRunner::invokeTrial);

            TripleStore store = new TripleStore(candidatesRoot);
            List<Path> candidates = store.candidates(endpointId);
            List<FailureDigest> digests = new ArrayList<>();
            int attempts = 0;
            for (Path candDir : candidates) {
                if (attempts >= budget) {
                    // 예산 소진 — 남은 후보는 원위치 보존(재시도 대상). 클래스 Javadoc "예산 소진 semantics" 참고.
                    break;
                }
                attempts++;
                FailureDigest digest;
                int status;
                try {
                    TrialRunner.TrialOutcome outcome =
                            trialRunner.runCandidate(endpoint, candDir, happySeeds, report);
                    if (outcome.promoted()) {
                        Path promoted = store.promote(candDir);
                        log.info("trial: {} promoted -> {} (status {})", candDir, promoted, outcome.status());
                        return 0;
                    }
                    digest = outcome.digest();
                    status = outcome.status();
                } catch (Exception e) {
                    // 후보 단위 격리: seed INSERT 도중 실패·malformed stubs.json 등 invoke 이전
                    // 단계의 예외는 이 후보만 failed 처리하고 다음 후보로 진행한다(루프 전체 중단 금지).
                    // TrialRunner.runCandidate의 finally가 이미 부분 삽입분/등록 stub을 정리했다.
                    log.warn("trial: {} threw before/during invoke (candidate 격리, 다음 후보로 진행): {}",
                            candDir, e.toString(), e);
                    digest = FailureDigest.forError(candDir.toString(), e);
                    status = -1;
                }
                digests.add(digest);
                String digestJson = digest == null ? ""
                        : Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(digest);
                store.fail(candDir, digestJson);
                log.warn("trial: {} failed (status {})", candDir, status);
            }
            Path finalReport = Files.createDirectories(candidatesRoot.resolve(endpointId).resolve("failed"))
                    .resolve("digest-final.json");
            Files.writeString(finalReport,
                    Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(digests));
            log.warn("trial: {} candidate(s) exhausted for {} (budget={}) -> {}",
                    digests.size(), endpointId, budget, finalReport);
            return 3;
        }
    }

    /**
     * trial CLI 전용 경량 {@link SutHandle} — 이미 떠 있는 SUT에 attach한다(프로세스를 소유·기동하지
     * 않음, {@link #stop()}은 no-op). 로그 슬라이스는 {@code --sut-log-file}로 지정한 파일을 매 호출 시
     * 전체 재로드해 byte 오프셋으로 자른다(REQ-014 stackExcerpt 근거). 파일 미지정 시 로그 구간은 항상
     * 빈 문자열(FailureDigest는 여전히 raw 응답/상태로 산출되고, mappedGuard는 로그 기반 스택 매칭 없이
     * literal-fallback만 시도한다). 패키지 가시성 — {@code LogFileSutHandleTest}(byte-오프셋 슬라이싱
     * 회귀)가 직접 생성해 검증한다.
     */
    static final class LogFileSutHandle implements SutHandle {
        private final String baseUri;
        private final Path logFile;

        LogFileSutHandle(String baseUri, Path logFile) {
            this.baseUri = baseUri;
            this.logFile = logFile;
        }

        @Override
        public String baseUri() {
            return baseUri;
        }

        @Override
        public long logOffset() {
            try {
                return logFile != null && Files.exists(logFile) ? Files.size(logFile) : 0;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public String readLog() {
            return readLogRange(0, Long.MAX_VALUE);
        }

        @Override
        public String readLogFrom(long offset) {
            return readLogRange(offset, Long.MAX_VALUE);
        }

        @Override
        public String readLogRange(long start, long end) {
            if (logFile == null || !Files.exists(logFile)) {
                return "";
            }
            try {
                byte[] bytes = Files.readAllBytes(logFile);
                int from = (int) Math.min(Math.max(start, 0), bytes.length);
                int to = (int) Math.min(Math.max(end, 0), bytes.length);
                return from >= to ? "" : new String(bytes, from, to - from, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void stop() {
            // no-op — attach 모드: 이 CLI가 SUT 프로세스를 소유하지 않는다.
        }
    }

    /** --sut-src + (optional) --sut-resources → SourceRoots. 멀티 루트 + --incremental-base 조합은 거부. */
    static SourceRoots buildSourceRoots(Map<String, String> options) {
        String sutSrcArg = required(options, "--sut-src");
        Path resourcesArg = options.containsKey("--sut-resources")
                ? Path.of(options.get("--sut-resources")) : null;
        SourceRoots roots = SutSrcResolver.resolve(sutSrcArg, resourcesArg);
        if (roots.isMulti() && options.get("--incremental-base") != null) {
            throw new IllegalArgumentException(
                    "--sut-src multi-root is not supported with --incremental-base (v1)");
        }
        if (roots.isMulti() && resourcesArg == null) {
            log.info("--sut-resources not given; falling back to each source root's sibling 'resources'");
        }
        return roots;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        int start = args.length > 0 && !args[0].startsWith("--") ? 1 : 0;  // "build" 서브커맨드 허용
        for (int i = start; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            // 다음 토큰이 값이면 소비, 아니면 값 없는 플래그(예: --with-redis)
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(args[i], args[i + 1]);
                i++;
            } else {
                options.put(args[i], "");
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return value;
    }

    /**
     * CLI 사용법 문자열. 주요 옵션·glob 문법·혼용 예시를 포함한다.
     *
     * <p>glob 문법 (NIO glob: 의미):
     * <ul>
     *   <li>{@code *}  — 한 경로 세그먼트 내 임의 문자열 ({@code /} 미포함)</li>
     *   <li>{@code **} — {@code /}를 횡단하는 임의 문자열 (재귀)</li>
     *   <li>{@code {a,b}} — 택일 (브레이스 확장)</li>
     * </ul>
     */
    public static String usage() {
        return """
                Usage: graph-rag-builder build --sut-src <pattern[,pattern...]>
                         --sut-jar <bootjar> --out <graph-dir> --sut-compose <compose.yml>
                         [OPTIONS]

                  --sut-src <pattern[,pattern...]>
                        소스 루트(들). 리터럴 경로 / glob 패턴 / 혼용을 콤마로 구분.
                        glob: * = 세그먼트 내, ** = 재귀, {a,b} = 택일

                        예) 단일 리터럴:  src/main/java
                            브레이스:     src/main/java/com/app/{feature,common}
                            콤마 혼용:    src/main/java/com/app/orders, src/main/java/com/app/common/**
                        ※ * 는 / 를 넘지 않음. 재귀 검색은 ** 사용.
                        ※ --incremental-base 와 멀티 루트 동시 사용 불가 (v1 제한).

                  --endpoint <spec[,spec...]>
                        탐색할 단위. 정확 id, "METHOD /path", glob 패턴을 혼용 가능.
                        glob: * = 세그먼트 내, ** = 재귀, {a,b} = 택일

                        예) 정확 id:     post-api-orders
                            METHOD/path: GET /api/users/123
                            glob(재귀):  GET /api/users/**
                            glob(prefix):post-api-orders-*
                            혼용:        post-api-orders, GET /api/users/**, post-api-orders-*
                        ※ 정확 매칭이 우선. glob 메타문자(* ** { [)가 없으면 정확 매칭만.
                        ※ --incremental-base 동반 시 비선택 단위는 base에서 이월.

                  [주요 옵션 생략 — BuilderCli 소스 및 docs/03-graph-rag-builder.md 참조]
                """;
    }

    /**
     * coverage(=pjacoco control) 호스트 포트 옵션. 비파괴 alias:
     * {@code --coverage-port}를 우선하고, 없으면 deprecated {@code --jacoco-port}를 1회 경고와 함께 수락한다.
     * (이 포트는 jacoco 전용이 아니라 pjacoco control port로 공유되는 plumbing이다 — REQ-P010.)
     */
    static String coveragePortOption(Map<String, String> options) {
        String coverage = options.get("--coverage-port");
        if (coverage != null && !coverage.isEmpty()) {
            return coverage;
        }
        String legacy = options.get("--jacoco-port");   // deprecated alias
        if (legacy != null && !legacy.isEmpty()) {
            log.warn("--jacoco-port is a deprecated alias; use --coverage-port (pjacoco control port)");
            return legacy;
        }
        throw new IllegalArgumentException("missing required option: --coverage-port");
    }

    /** --trace-mode otel|sleuth|none (미지정 시 기본 otel). 그 외 값은 거부. */
    static String traceMode(String value) {
        if (value == null) {
            return "otel";   // OTEL이 기본. sleuth(레거시 B3 로그상관)/none(로그 byte-offset)은 명시.
        }
        if (!value.equals("otel") && !value.equals("sleuth") && !value.equals("none")) {
            throw new IllegalArgumentException(
                    "--trace-mode must be 'otel', 'sleuth', or 'none', got: " + value);
        }
        return value;
    }

    /**
     * REQ-012: 가드 (classFqn, method)가 reachable 집합에 속하는지 판정한다.
     * 정확 일치 우선, 이후 noClasspath 미해소 케이스를 위해 simpleName endsWith 폴백을 적용한다.
     *
     * <p>폴백 규칙: reachable 엔트리의 타입 FQN이 simpleName만 해소된 경우
     * ("ReservationService") 와 가드 classFqn("com.example.ReservationService")이 같은 이름이면
     * {@code classFqn.endsWith("." + reachableType)} 으로 매칭한다. 반대 방향(가드가 simpleName,
     * reachable이 FQN)도 동일하게 처리한다.
     *
     * <p>동명 클래스가 여러 패키지에 있으면 과귀속 가능 — best-effort, 매칭 실패 시 귀속 0.
     */
    /**
     * REQ-008: state-guard/conjunction 추출 ablation 게이트. {@code GRB_STATE_GUARDS} 환경변수가
     * "off"(대소문자 무시)면 비활성(false) — allStateGuards·allStateGuardConjunctions 모두 빈 리스트로
     * 만들어 변종 pass를 완전 no-op으로 만든다. null/미설정/그 외 값은 활성(true).
     */
    static boolean stateGuardsEnabled(String grbStateGuardsEnv) {
        return !"off".equalsIgnoreCase(grbStateGuardsEnv);
    }

    static boolean isReachable(Set<Map.Entry<String, String>> reachable, String classFqn, String method) {
        return SynthesisMethodFilter.matches(reachable, classFqn, method);
    }
}
