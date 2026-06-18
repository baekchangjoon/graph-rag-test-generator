package io.graphrag.builder.cli;

import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.JacocoAgent;
import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.AttachedComposeEnvironment;
import io.graphrag.builder.env.ComposeInspector;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.ExplorationEnvironment;
import io.graphrag.builder.env.OverrideComposeGenerator;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.EndpointIndexer;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.LiteralCandidateExtractor;
import io.graphrag.builder.index.MapperXmlIndexer;
import io.graphrag.builder.index.ResponseDtoIndexer;
import io.graphrag.builder.index.ValidationConstraintExtractor;
import io.graphrag.builder.run.AuthConfig;
import io.graphrag.builder.run.AuthTokenProvider;
import io.graphrag.builder.run.EndpointExplorationRunner;
import io.graphrag.builder.store.JsonFileGraphStore;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 도구 1 진입점 (Phase 2: 분기 탐색 + MyBatis + 외부 HTTP 캡처).
 * build --sut-src <dir> --sut-jar <jar> --out <graph-dir> --sut-compose <docker-compose.yml>
 *       [--sut-resources <dir>] [--sut-id id] [--commit-sha sha]
 *       [--db-image <image>] [--budget-requests 60] [--manual-paths <dir>]
 *       [--external-stubs <dir>] [--sut-env KEY={{wiremock}}[,KEY2=V2]]
 *       [--incremental-base <prev-graph-dir> --changed-files <list-file>]
 *       [--auth-login-path /api/auth/login --auth-user admin --auth-pass password]
 *       [--auth-token-field token --auth-header Authorization --auth-scheme Bearer]
 */
public final class BuilderCli {

    private static final Logger log = LoggerFactory.getLogger(BuilderCli.class);

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("coverage")) {
            runCoverageReport(parseArgs(args));
            return;
        }
        Map<String, String> options = parseArgs(args);
        Path sutSrc = Path.of(required(options, "--sut-src"));
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
                        Integer.parseInt(required(options, "--jacoco-port")),
                        required(options, "--jdbc-url"),
                        options.get("--kafka-bootstrap"),
                        options.getOrDefault("--health-path", "/actuator/health"),
                        Integer.parseInt(options.getOrDefault("--ready-timeout", "120")))
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
            endpointSelectors = java.util.Arrays.stream(options.get("--endpoint").split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (endpointSelectors.isEmpty()) {
                throw new IllegalArgumentException("--endpoint given but no non-blank spec(s) provided");
            }
        }

        BuildConfig config = new BuildConfig(
                sutSrc,
                Path.of(options.getOrDefault("--sut-resources",
                        sutSrc.resolveSibling("resources").toString())),
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
                sqlCaptureMode(options.get("--sql-capture")));

        GraphAsset asset = build(config);
        log.info("graph saved: {} endpoints, {} paths, {} sql, {} http, {} tables, {} mappers -> {}",
                asset.endpoints().size(), asset.paths().size(), asset.sql().size(),
                asset.httpCalls().size(), asset.tables().size(), asset.mappers().size(),
                config.out().resolve("graph.json"));
    }

    public static GraphAsset build(BuildConfig config) throws Exception {
        log.info("indexing endpoints from {}", config.sutSrc());
        IndexResult index = new EndpointIndexer().index(config.sutSrc(), config.authConfig());
        io.graphrag.builder.index.WsIndexResult wsIndex =
                new io.graphrag.builder.index.WsEndpointIndexer().index(config.sutSrc());
        io.graphrag.builder.index.KafkaIndexResult kafkaIndex =
                new io.graphrag.builder.index.KafkaListenerIndexer().index(config.sutSrc());
        List<MapperStatement> mappers = Files.isDirectory(config.sutResources())
                ? new MapperXmlIndexer().index(config.sutResources())
                : List.<MapperStatement>of();
        List<Set<String>> responseDtoFieldSets = new ResponseDtoIndexer().extract(config.sutSrc());
        log.info("found {} endpoint(s), {} mapper statement(s), {} response dto shape(s)",
                index.endpoints().size(), mappers.size(), responseDtoFieldSets.size());

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
        // SUT 전체 도달 분기 집계: 전 엔드포인트가 커버한 whole-app 분기 합집합
        Set<io.graphrag.model.BranchRef> coveredAppBranches = new LinkedHashSet<>();
        // 탐색 전체의 line+branch 집계용: 엔드포인트별 누적 exec를 OR 병합한 run-wide 스토어.
        org.jacoco.core.data.ExecutionDataStore runWideExec = new org.jacoco.core.data.ExecutionDataStore();
        ExplorationAccumulators acc = new ExplorationAccumulators(
                paths, sql, httpCalls, wsExchanges, kafkaExchanges, allSeeds, reportEntries,
                coveredAppBranches, runWideExec);

        // enum 상수 맵: 순수 소스 파싱(SUT/Docker 불요) → 분석 환경 기동 전 1회.
        Map<String, List<String>> enumConstants =
                new io.graphrag.builder.index.EnumConstantExtractor().extract(config.sutSrc());

        Path workDir = Files.createDirectories(config.out().resolve("work"));
        JacocoAgent jacoco = JacocoAgent.prepare(workDir);
        OtelAgent otel = OtelAgent.prepare(workDir);

        ExplorationResult result;
        if (config.attach() != null) {
            result = runAttached(config, jacoco, otel, workDir, mybatisLogLevels,
                    index, wsIndex, kafkaIndex, mappers, responseDtoFieldSets, plan, enumConstants, acc);
        } else {
            SutOptions sutOptions = new SutOptions(
                    jacoco.javaToolOptions() + " " + otel.javaToolOptions(),
                    mybatisLogLevels,
                    otel.env(config.sutId()),
                    config.sutJavaHome());
            try (AnalysisEnvironment env =
                    new AnalysisEnvironment(config.dbConfig(), config.withRedis(), config.withKafka())) {
                boolean otelSqlCapture = "otel".equals(config.sqlCapture());
                env.start(config.sutJar(), workDir, sutOptions,
                        config.externalStubsDir(), config.sutEnv(),
                        otelSqlCapture ? otel : null, config.sutId());
                env.coverageEndpoint("localhost", jacoco.tcpPort());
                result = explore(env, config, index, wsIndex, kafkaIndex, mappers,
                        responseDtoFieldSets, plan, enumConstants, acc);
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
                                coveredAppClasses)));

        GraphAsset asset = new GraphAsset(config.sutId(), config.commitSha(),
                index.endpoints(), paths, sql, tables, mappers, httpCalls,
                wsIndex.endpoints(), wsExchanges, kafkaIndex.consumers(), kafkaExchanges, allSeeds);
        new JsonFileGraphStore(config.out()).save(asset);
        new io.graphrag.builder.store.PartitionedGraphStore(config.out()).save(asset);
        return asset;
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
            org.jacoco.core.data.ExecutionDataStore runWideExec) {
    }

    /** attach 모드: 사용자 compose + 생성 override 로 SUT를 띄우고 동일한 explore()를 돌린다. */
    private static ExplorationResult runAttached(BuildConfig config, JacocoAgent jacoco, OtelAgent otel, Path workDir,
            Map<String, String> mybatisLogLevels, IndexResult index,
            io.graphrag.builder.index.WsIndexResult wsIndex,
            io.graphrag.builder.index.KafkaIndexResult kafkaIndex, List<MapperStatement> mappers,
            List<Set<String>> responseDtoFieldSets, IncrementalPlan plan,
            Map<String, List<String>> enumConstants, ExplorationAccumulators acc) throws Exception {
        AttachConfig at = config.attach();
        Path agentsDir = Files.createDirectories(workDir.resolve("agents"));
        // jacoco/otel jar 를 컨테이너로 mount 할 호스트 디렉터리로 모은다
        Files.copy(jacoco.agentJar(), agentsDir.resolve("jacocoagent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(otel.agentJar(), agentsDir.resolve("otel-javaagent.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        int jacocoContainerPort = 6300;
        String jto = JacocoAgent.containerJavaToolOptions("/grb-agents/jacocoagent.jar", jacocoContainerPort)
                + " -javaagent:/grb-agents/otel-javaagent.jar";

        // OTEL SQL 캡처(attach): 호스트 OTLP 리시버를 wildcard bind + per-run secret으로 띄우고,
        // 컨테이너가 host.docker.internal:<port>로 도달하도록 override에 otlp export env + extra_hosts 주입.
        // log 모드면 기존 동작(exporter none + baggage, host-gateway/batch 미주입).
        // host-gateway 는 외부 HTTP 캡처(모든 attach 모드)와 OTLP receiver 도달에 모두 필요 — 항상 1회 점검.
        warnIfHostGatewayUnsupported();
        boolean otelSqlCapture = "otel".equals(config.sqlCapture());
        io.graphrag.builder.capture.otlp.OtlpTraceReceiver otlpReceiver = null;
        Map<String, String> otelEnv;
        if (otelSqlCapture) {
            String secret = newOtlpSecret();
            otlpReceiver = new io.graphrag.builder.capture.otlp.OtlpTraceReceiver();
            otlpReceiver.start("0.0.0.0", secret);
            otelEnv = otel.otlpEnv(config.sutId(), otlpReceiver.hostEndpoint(), secret);
            log.info("OTEL SQL capture (attach): otlp receiver {} (container reaches via {})",
                    otlpReceiver.endpoint(), otlpReceiver.hostEndpoint());
        } else {
            otelEnv = otel.env(config.sutId());   // OTEL env 동등 주입(exporter none + baggage)
        }

        // 외부 HTTP 캡처(모든 attach 모드): 호스트 WireMock을 per-run token으로 띄우고, SUT env의
        // {{wiremock}}을 컨테이너가 도달 가능한 host.docker.internal:<port>[/token]로 치환한다.
        String httpToken = newOtlpSecret();   // reuse the per-run secret generator
        io.graphrag.builder.env.HttpCaptureServer httpCapture = new io.graphrag.builder.env.HttpCaptureServer();
        httpCapture.start(config.externalStubsDir(), httpToken);
        java.util.Map<String, String> mergedEnv = new java.util.LinkedHashMap<>(otelEnv);
        config.sutEnv().forEach((k, v) -> mergedEnv.put(k,
                v.replace(io.graphrag.builder.env.AnalysisEnvironment.WIREMOCK_PLACEHOLDER, httpCapture.hostBaseUrl())));
        log.info("attach external HTTP capture: wiremock {} (container reaches host.docker.internal:{})",
                httpCapture.baseUrl(), httpCapture.port());

        // httpCapture.start() 이후 발생하는 모든 작업(override 생성/쓰기, envCfg, env 생성/start/explore)을
        // 같은 try로 감싼다 → env로 소유권을 넘기기 전에 throw 되더라도 finally가 httpCapture/otlpReceiver를
        // 정확히 1회 정리한다(성공적으로 넘긴 뒤에는 env의 try-with-resources가 소유).
        boolean handedOff = false;
        try {
            String overrideYaml = new OverrideComposeGenerator().generate(
                    new OverrideComposeGenerator.Spec(at.appService(), agentsDir.toAbsolutePath().toString(),
                            at.appContainerPort(), at.appHostPort(), jacocoContainerPort, at.jacocoHostPort(),
                            jto, mybatisLogLevels, mergedEnv, true, otelSqlCapture));
            Path overridePath = workDir.resolve("attach-override.yml");
            Files.writeString(overridePath, overrideYaml);

            var envCfg = new AttachedComposeEnvironment.Config(at.userCompose(), overridePath,
                    at.appService(), "grb-attach-" + config.sutId(),
                    "http://localhost:" + at.appHostPort(),
                    at.jdbcUrl(), config.dbConfig().user(), config.dbConfig().password(),
                    "localhost", at.jacocoHostPort(), at.kafkaBootstrap(),
                    at.healthPath(), at.readyTimeoutSeconds());

            AttachedComposeEnvironment env = new AttachedComposeEnvironment(
                    envCfg, config.dbConfig().type(), otlpReceiver, httpCapture);
            handedOff = true;
            try (env) {
                env.start(workDir);
                return explore(env, config, index, wsIndex, kafkaIndex, mappers,
                        responseDtoFieldSets, plan, enumConstants, acc);
            }
        } finally {
            if (!handedOff) {
                httpCapture.close();
                if (otlpReceiver != null) { otlpReceiver.stop(); }
            }
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

    /** SUT 환경(env) 위에서 Kafka/HTTP/WS 탐색을 돌리고 acc를 채운다. analysis/attach 공통. */
    private static ExplorationResult explore(ExplorationEnvironment env, BuildConfig config,
                                             IndexResult index,
                                             io.graphrag.builder.index.WsIndexResult wsIndex,
                                             io.graphrag.builder.index.KafkaIndexResult kafkaIndex,
                                             List<MapperStatement> mappers,
                                             List<Set<String>> responseDtoFieldSets,
                                             IncrementalPlan plan,
                                             Map<String, List<String>> enumConstants,
                                             ExplorationAccumulators acc) throws Exception {
        List<ExploredPath> paths = acc.paths();
        List<CapturedSql> sql = acc.sql();
        List<CapturedHttpCall> httpCalls = acc.httpCalls();
        List<io.graphrag.model.WsExchange> wsExchanges = acc.wsExchanges();
        List<io.graphrag.model.KafkaExchange> kafkaExchanges = acc.kafkaExchanges();
        List<RequiredSeed> allSeeds = acc.allSeeds();
        List<ExplorationReport.EndpointExploration> reportEntries = acc.reportEntries();
        Set<io.graphrag.model.BranchRef> coveredAppBranches = acc.coveredAppBranches();
        org.jacoco.core.data.ExecutionDataStore runWideExec = acc.runWideExec();
        int totalAppBranches;
        List<TableSchema> tables;

        AuthTokenProvider authProvider = config.authConfig() == null ? null
                : new AuthTokenProvider(env.sut().baseUri(), config.authConfig(), config.requestHeaders());

        try (Connection connection = env.openConnection()) {
            tables = new io.graphrag.builder.schema.SchemaExtractor().extract(connection);
            log.info("extracted schema: {} table(s)", tables.size());

            CoverageClient coverageClient = new CoverageClient(env.coverageHost(), env.coveragePort());
            BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer(config.sutJar());
            // BOOT-INF/classes 전체 분기 = app 커버리지 분모 (1회 산출)
            totalAppBranches = analyzer.analyze(
                    new org.jacoco.core.data.ExecutionDataStore()).totalBranches();
            ConstraintExtractor constraintExtractor = new ConstraintExtractor();
            LiteralCandidateExtractor literalExtractor = new LiteralCandidateExtractor();
            // 비교식(분기 조건)은 전 계층 1회 추출 — rec-1(solverRelevantMissed) 라인 매칭용.
            List<ConstraintExtractor.Comparison> allComparisons =
                    constraintExtractor.extractComparisons(config.sutSrc());
            // 메서드 내 && conjunction(다필드 동시 가드) — joint 입력 합성 근거. 전 계층 1회.
            List<ConstraintExtractor.Conjunction> allConjunctions =
                    constraintExtractor.extractConjunctions(config.sutSrc());
            // 가드에서 직접 유래한 컬럼→유효 enum 상수 (시드 행 읽기 500 방지, Bug 3).
            Map<String, List<String>> enumColumns =
                    constraintExtractor.extractEnumColumns(config.sutSrc());
            // 상태 의존 가드(TEMPORAL/ENUM) — by-id 양 arm 시드 변종 근거 (Stage 4). 전 계층 1회.
            // GRB_STATE_GUARDS=off 면 빈 리스트 → 변종 pass 완전 no-op(ablation/회귀 control).
            List<ConstraintExtractor.StateGuard> allStateGuards =
                    "off".equalsIgnoreCase(System.getenv("GRB_STATE_GUARDS"))
                            ? List.of() : constraintExtractor.extractStateGuards(config.sutSrc());
            // 입력 후보 = 교체가능 오라클들의 합집합 (정적 리터럴 + ASM+Z3 concolic).
            // GRB_ORACLE=static 이면 concolic 제외 (오라클 기여도 ablation 측정용).
            io.graphrag.builder.oracle.InputOracle.SutCode sutCode =
                    new io.graphrag.builder.oracle.InputOracle.SutCode(config.sutSrc(), config.sutJar());
            boolean useConcolic = !"static".equalsIgnoreCase(System.getenv("GRB_ORACLE"));
            io.graphrag.builder.oracle.InputCandidates inputCandidates =
                    new io.graphrag.builder.oracle.StaticLiteralOracle().analyze(sutCode);
            if (useConcolic) {
                inputCandidates = inputCandidates.merge(
                        new io.graphrag.builder.oracle.ConcolicOracle().analyze(sutCode));
            }
            log.info("input oracles (concolic={}) → {} numeric field(s), {} string field(s)",
                    useConcolic, inputCandidates.numeric().size(), inputCandidates.strings().size());

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
            io.graphrag.builder.capture.SqlCaptureBackend sqlCapture =
                    "otel".equals(config.sqlCapture()) && env.otlpReceiver() != null
                            ? new io.graphrag.builder.capture.OtelSpanCapture(env.otlpReceiver(), env.sut(),
                                    new io.graphrag.builder.capture.TraceParent(traceRunId))
                            : new io.graphrag.builder.capture.LogParserCapture(env.sut());

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
                                connection, env.dbType(), kafkaBootstrap, coverageClient, sqlCapture);
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

            for (Endpoint endpoint : index.endpoints()) {
                if (!plan.shouldExplore(endpoint.id())) {
                    log.info("skip {} (partition clean; carrying over)", endpoint.id());
                    continue;
                }
                BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
                boolean hasPathParam = endpoint.params().stream()
                        .anyMatch(p -> p.kind() == io.graphrag.model.ParamKind.PATH);
                // body 없는 비-GET이라도 PATH param이 있으면 by-id 경로(DELETE /{id} 등)로 탐색
                // (happyInput이 path-id + 리소스 시드 합성). body도 path도 없을 때만 skip.
                if (shape == null && !endpoint.httpMethod().equals("GET") && !hasPathParam) {
                    log.warn("skip {} (no @RequestBody shape and no path param)", endpoint.id());
                    continue;
                }
                var conditions = constraintExtractor.extract(
                        config.sutSrc(), endpoint.handlerClass(), endpoint.handlerMethod());
                var literals = literalExtractor.extract(config.sutSrc(), endpoint.handlerClass());
                Map<String, List<ValidationConstraintExtractor.FieldConstraint>> fieldConstraints =
                        shape == null ? Map.of()
                                : new ValidationConstraintExtractor()
                                        .extract(config.sutSrc(), shape.javaType());
                EndpointExplorationRunner runner = new EndpointExplorationRunner(
                        env.sut(), connection, env.dbType(),
                        coverageClient, analyzer,
                        config.budgetRequests(), env.httpCapture(),
                        responseDtoFieldSets, literals,
                        authProvider, config.authConfig(), enumConstants, enumColumns,
                        config.requestHeaders(), sqlCapture);
                // 이 엔드포인트 handler에 귀속된 상태가드만 전달(per-endpoint 필터).
                List<ConstraintExtractor.StateGuard> endpointStateGuards = allStateGuards.stream()
                        .filter(g -> g.classFqn().equals(endpoint.handlerClass())
                                && g.method().equals(endpoint.handlerMethod()))
                        .toList();
                EndpointExplorationRunner.EndpointResult result =
                        runner.run(endpoint, shape, tables, conditions,
                                allComparisons, inputCandidates, fieldConstraints, allConjunctions,
                                endpointStateGuards,
                                index.validBodyEndpointIds().contains(endpoint.id()));
                paths.addAll(result.paths());
                sql.addAll(result.sql());
                httpCalls.addAll(result.httpCalls());
                allSeeds.addAll(result.seeds());
                reportEntries.add(result.report());
                result.cumulativeExec().accept(runWideExec);   // OR 병합 (line 집계용)
            }

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
                               int appContainerPort, int appHostPort, int jacocoHostPort,
                               String jdbcUrl, String kafkaBootstrap,
                               String healthPath, int readyTimeoutSeconds) {}

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

    /** --sql-capture otel|log (미지정 시 기본 otel). 그 외 값은 거부. */
    private static String sqlCaptureMode(String value) {
        if (value == null) {
            return "otel";   // PoC①②+수용1~4 green → OTEL이 기본. log는 --sql-capture log 폴백.
        }
        if (!value.equals("otel") && !value.equals("log")) {
            throw new IllegalArgumentException("--sql-capture must be 'otel' or 'log', got: " + value);
        }
        return value;
    }
}
