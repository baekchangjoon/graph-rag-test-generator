package io.graphrag.builder.cli;

import io.graphrag.builder.env.AnalysisEnvironment;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.EndpointIndexer;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.run.PathCaptureRunner;
import io.graphrag.builder.schema.SchemaExtractor;
import io.graphrag.builder.store.JsonFileGraphStore;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 도구 1 진입점.
 * build --sut-src <dir> --sut-jar <jar> --out <graph-dir> [--sut-id id] [--commit-sha sha]
 *       [--postgres-image postgres:15]
 */
public final class BuilderCli {

    private static final Logger log = LoggerFactory.getLogger(BuilderCli.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path sutSrc = Path.of(required(options, "--sut-src"));
        Path sutJar = Path.of(required(options, "--sut-jar"));
        Path out = Path.of(required(options, "--out"));
        String sutId = options.getOrDefault("--sut-id", "sut");
        String commitSha = options.getOrDefault("--commit-sha", "unknown");
        String postgresImage = options.getOrDefault("--postgres-image", "postgres:15");

        GraphAsset asset = build(sutSrc, sutJar, out, sutId, commitSha, postgresImage);
        log.info("graph saved: {} endpoints, {} paths, {} sql, {} tables -> {}",
                asset.endpoints().size(), asset.paths().size(), asset.sql().size(),
                asset.tables().size(), out.resolve("graph.json"));
    }

    public static GraphAsset build(Path sutSrc, Path sutJar, Path out, String sutId,
                                   String commitSha, String postgresImage) throws Exception {
        log.info("indexing endpoints from {}", sutSrc);
        IndexResult index = new EndpointIndexer().index(sutSrc);
        log.info("found {} endpoint(s)", index.endpoints().size());

        List<ExploredPath> paths = new ArrayList<>();
        List<CapturedSql> sql = new ArrayList<>();
        List<TableSchema> tables;

        Path workDir = Files.createDirectories(out.resolve("work"));
        try (AnalysisEnvironment env = new AnalysisEnvironment(postgresImage)) {
            env.start(sutJar, workDir);

            try (Connection connection = env.openConnection()) {
                tables = new SchemaExtractor().extract(connection);
                log.info("extracted schema: {} table(s)", tables.size());

                PathCaptureRunner runner = new PathCaptureRunner();
                for (Endpoint endpoint : index.endpoints()) {
                    BodyShape shape = bodyShapeFor(endpoint, index.bodyShapes());
                    if (shape == null) {
                        log.warn("skip {} (no @RequestBody shape; Phase 0 limitation)", endpoint.id());
                        continue;
                    }
                    PathCaptureRunner.CaptureResult result =
                            runner.capture(endpoint, shape, tables, env.sut(), connection);
                    paths.add(result.path());
                    sql.addAll(result.sql());
                }
            }
        }

        GraphAsset asset = new GraphAsset(sutId, commitSha, index.endpoints(), paths, sql, tables,
                List.of());
        new JsonFileGraphStore(out).save(asset);
        return asset;
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
