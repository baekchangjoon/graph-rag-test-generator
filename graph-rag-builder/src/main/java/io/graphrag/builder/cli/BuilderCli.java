package io.graphrag.builder.cli;

import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.JacocoAgent;
import io.graphrag.builder.coverage.OtelAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.ComposeInspector;
import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.EndpointIndexer;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.LiteralCandidateExtractor;
import io.graphrag.builder.index.MapperXmlIndexer;
import io.graphrag.builder.index.ResponseDtoIndexer;
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
        DbConfig dbConfig = ComposeInspector.detectDb(Path.of(sutComposeStr));
        if (options.containsKey("--db-image")) {
            dbConfig = new DbConfig(dbConfig.type(), options.get("--db-image"),
                    dbConfig.dbName(), dbConfig.user(), dbConfig.password());
        }

        AuthConfig authConfig = options.containsKey("--auth-login-path")
                ? new AuthConfig(options.get("--auth-login-path"),
                        options.getOrDefault("--auth-user", "admin"),
                        options.getOrDefault("--auth-pass", "password"),
                        options.getOrDefault("--auth-token-field", "token"),
                        options.getOrDefault("--auth-header", "Authorization"),
                        options.getOrDefault("--auth-scheme", "Bearer"),
                        java.util.List.of())
                : null;

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
                authConfig);

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
        List<MapperStatement> mappers = Files.isDirectory(config.sutResources())
                ? new MapperXmlIndexer().index(config.sutResources())
                : List.<MapperStatement>of();
        List<Set<String>> responseDtoFieldSets = new ResponseDtoIndexer().extract(config.sutSrc());
        log.info("found {} endpoint(s), {} mapper statement(s), {} response dto shape(s)",
                index.endpoints().size(), mappers.size(), responseDtoFieldSets.size());

        IncrementalPlan plan = IncrementalPlan.exploreAll();
        if (config.incrementalBase() != null) {
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
        List<RequiredSeed> allSeeds = new ArrayList<>();
        List<ExplorationReport.EndpointExploration> reportEntries = new ArrayList<>();
        List<TableSchema> tables;

        Path workDir = Files.createDirectories(config.out().resolve("work"));
        JacocoAgent jacoco = JacocoAgent.prepare(workDir);
        OtelAgent otel = OtelAgent.prepare(workDir);
        SutOptions sutOptions = new SutOptions(
                jacoco.javaToolOptions() + " " + otel.javaToolOptions(),
                mybatisLogLevels,
                otel.env(config.sutId()));

        try (AnalysisEnvironment env = new AnalysisEnvironment(config.dbConfig())) {
            env.start(config.sutJar(), workDir, sutOptions,
                    config.externalStubsDir(), config.sutEnv());

            AuthTokenProvider authProvider = config.authConfig() == null ? null
                    : new AuthTokenProvider(env.sut().baseUri(), config.authConfig());

            try (Connection connection = env.openConnection()) {
                tables = new io.graphrag.builder.schema.SchemaExtractor().extract(connection);
                log.info("extracted schema: {} table(s)", tables.size());

                CoverageClient coverageClient = new CoverageClient("localhost", jacoco.tcpPort());
                BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer(config.sutJar());
                ConstraintExtractor constraintExtractor = new ConstraintExtractor();
                LiteralCandidateExtractor literalExtractor = new LiteralCandidateExtractor();

                for (Endpoint endpoint : index.endpoints()) {
                    if (!plan.shouldExplore(endpoint.id())) {
                        log.info("skip {} (partition clean; carrying over)", endpoint.id());
                        continue;
                    }
                    BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
                    if (shape == null && !endpoint.httpMethod().equals("GET")) {
                        log.warn("skip {} (no @RequestBody shape; not yet supported)", endpoint.id());
                        continue;
                    }
                    var conditions = constraintExtractor.extract(
                            config.sutSrc(), endpoint.handlerClass(), endpoint.handlerMethod());
                    var literals = literalExtractor.extract(config.sutSrc(), endpoint.handlerClass());
                    EndpointExplorationRunner runner = new EndpointExplorationRunner(
                            env.sut(), connection, env.dbType(),
                            coverageClient, analyzer,
                            config.budgetRequests(), env.httpCapture(),
                            responseDtoFieldSets, literals,
                            authProvider, config.authConfig());
                    EndpointExplorationRunner.EndpointResult result =
                            runner.run(endpoint, shape, tables, conditions);
                    paths.addAll(result.paths());
                    sql.addAll(result.sql());
                    httpCalls.addAll(result.httpCalls());
                    allSeeds.addAll(result.seeds());
                    reportEntries.add(result.report());
                }

                io.graphrag.builder.run.WsCaptureRunner wsRunner =
                        new io.graphrag.builder.run.WsCaptureRunner(
                                env.sut(), connection, env.dbType());
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
                }
            }
        }

        paths.addAll(plan.carriedPaths());
        sql.addAll(plan.carriedSql());
        httpCalls.addAll(plan.carriedHttpCalls());
        wsExchanges.addAll(plan.carriedWsExchanges());

        mergeManualPaths(config.manualPathsDir(), paths);

        Files.writeString(config.out().resolve("exploration-report.json"),
                Json.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ExplorationReport(reportEntries)));

        GraphAsset asset = new GraphAsset(config.sutId(), config.commitSha(),
                index.endpoints(), paths, sql, tables, mappers, httpCalls,
                wsIndex.endpoints(), wsExchanges, allSeeds);
        new JsonFileGraphStore(config.out()).save(asset);
        new io.graphrag.builder.store.PartitionedGraphStore(config.out()).save(asset);
        return asset;
    }

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
                .filter(p -> p.kind() == io.graphrag.model.ParamKind.BODY)
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

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        int start = args.length > 0 && !args[0].startsWith("--") ? 1 : 0;  // "build" 서브커맨드 허용
        for (int i = start; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required option: " + key);
        }
        return value;
    }
}
