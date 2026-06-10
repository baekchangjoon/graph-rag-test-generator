package io.graphrag.builder.cli;

import io.graphrag.builder.coverage.BranchCoverageAnalyzer;
import io.graphrag.builder.coverage.CoverageClient;
import io.graphrag.builder.coverage.JacocoAgent;
import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.env.SutOptions;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ConstraintExtractor;
import io.graphrag.builder.index.EndpointIndexer;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.MapperXmlIndexer;
import io.graphrag.builder.run.EndpointExplorationRunner;
import io.graphrag.builder.store.JsonFileGraphStore;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExplorationReport;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
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
import java.util.stream.Stream;

/**
 * 도구 1 진입점 (Phase 1: 분기 탐색 + MyBatis).
 * build --sut-src <dir> --sut-jar <jar> --out <graph-dir>
 *       [--sut-resources <dir>] [--sut-id id] [--commit-sha sha]
 *       [--postgres-image postgres:15] [--budget-requests 60] [--manual-paths <dir>]
 */
public final class BuilderCli {

    private static final Logger log = LoggerFactory.getLogger(BuilderCli.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path sutSrc = Path.of(required(options, "--sut-src"));
        Path sutJar = Path.of(required(options, "--sut-jar"));
        Path out = Path.of(required(options, "--out"));
        Path sutResources = Path.of(options.getOrDefault("--sut-resources",
                sutSrc.resolveSibling("resources").toString()));
        String manualPaths = options.get("--manual-paths");

        GraphAsset asset = build(sutSrc, sutResources, sutJar, out,
                options.getOrDefault("--sut-id", "sut"),
                options.getOrDefault("--commit-sha", "unknown"),
                options.getOrDefault("--postgres-image", "postgres:15"),
                Integer.parseInt(options.getOrDefault("--budget-requests", "60")),
                manualPaths == null ? null : Path.of(manualPaths));
        log.info("graph saved: {} endpoints, {} paths, {} sql, {} tables, {} mappers -> {}",
                asset.endpoints().size(), asset.paths().size(), asset.sql().size(),
                asset.tables().size(), asset.mappers().size(), out.resolve("graph.json"));
    }

    public static GraphAsset build(Path sutSrc, Path sutResources, Path sutJar, Path out,
                                   String sutId, String commitSha, String postgresImage,
                                   int budgetRequests, Path manualPathsDir) throws Exception {
        log.info("indexing endpoints from {}", sutSrc);
        IndexResult index = new EndpointIndexer().index(sutSrc);
        List<MapperStatement> mappers = Files.isDirectory(sutResources)
                ? new MapperXmlIndexer().index(sutResources)
                : List.<MapperStatement>of();
        log.info("found {} endpoint(s), {} mapper statement(s)",
                index.endpoints().size(), mappers.size());

        Map<String, String> mybatisLogLevels = new LinkedHashMap<>();
        mappers.forEach(m -> mybatisLogLevels.put(m.namespace(), "TRACE"));

        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> sql = new ArrayList<>();
        List<ExplorationReport.EndpointExploration> reportEntries = new ArrayList<>();
        List<TableSchema> tables;

        Path workDir = Files.createDirectories(out.resolve("work"));
        JacocoAgent agent = JacocoAgent.prepare(workDir);

        try (AnalysisEnvironment env = new AnalysisEnvironment(postgresImage)) {
            env.start(sutJar, workDir, new SutOptions(agent.javaToolOptions(), mybatisLogLevels));

            try (Connection connection = env.openConnection()) {
                tables = new io.graphrag.builder.schema.SchemaExtractor().extract(connection);
                log.info("extracted schema: {} table(s)", tables.size());

                CoverageClient coverageClient = new CoverageClient("localhost", agent.tcpPort());
                BranchCoverageAnalyzer analyzer = new BranchCoverageAnalyzer(sutJar);
                EndpointExplorationRunner runner = new EndpointExplorationRunner(
                        env.sut(), connection, coverageClient, analyzer, budgetRequests);
                ConstraintExtractor constraintExtractor = new ConstraintExtractor();

                for (Endpoint endpoint : index.endpoints()) {
                    BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
                    if (shape == null) {
                        log.warn("skip {} (no @RequestBody shape; not yet supported)", endpoint.id());
                        continue;
                    }
                    var conditions = constraintExtractor.extract(
                            sutSrc, endpoint.handlerClass(), endpoint.handlerMethod());
                    EndpointExplorationRunner.EndpointResult result =
                            runner.run(endpoint, shape, tables, conditions);
                    paths.addAll(result.paths());
                    sql.addAll(result.sql());
                    reportEntries.add(result.report());
                }
            }
        }

        mergeManualPaths(manualPathsDir, paths);

        Files.writeString(out.resolve("exploration-report.json"),
                Json.mapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(new ExplorationReport(reportEntries)));

        GraphAsset asset = new GraphAsset(sutId, commitSha, index.endpoints(), paths, sql,
                tables, mappers, List.of());
        new JsonFileGraphStore(out).save(asset);
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
