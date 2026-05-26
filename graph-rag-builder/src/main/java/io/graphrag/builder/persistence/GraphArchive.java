package io.graphrag.builder.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Phase 0/1/2 파일 기반 그래프 영속.
 *
 * <p>Phase 6에서 Neo4j 등 그래프 저장소로 교체. 인터페이스는 동일 유지하도록 SPI 도입.
 *
 * <p>파일 레이아웃:
 * <pre>
 * {baseDir}/endpoints.json         — List&lt;Endpoint&gt;
 * {baseDir}/paths.json             — List&lt;ExploredPath&gt;          (Phase 1)
 * {baseDir}/captured_sql.json      — List&lt;CapturedSql&gt;
 * {baseDir}/captured_http.json     — List&lt;CapturedHttpCall&gt;     (Phase 2)
 * </pre>
 */
public final class GraphArchive {

    private static final String ENDPOINTS_FILE = "endpoints.json";
    private static final String PATHS_FILE = "paths.json";
    private static final String CAPTURED_SQL_FILE = "captured_sql.json";
    private static final String CAPTURED_HTTP_FILE = "captured_http.json";

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final Path baseDir;
    private final ConcurrentMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ExploredPath> pathsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<CapturedSql>> capturedSqlByPath = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<CapturedHttpCall>> capturedHttpByPath = new ConcurrentHashMap<>();

    public GraphArchive(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    }

    public static GraphArchive load(Path baseDir) throws IOException {
        GraphArchive archive = new GraphArchive(baseDir);

        Path endpointsPath = baseDir.resolve(ENDPOINTS_FILE);
        if (Files.exists(endpointsPath)) {
            List<Endpoint> list = MAPPER.readValue(
                    Files.readAllBytes(endpointsPath),
                    new TypeReference<List<Endpoint>>() {});
            for (Endpoint e : list) archive.endpoints.put(e.id(), e);
        }

        Path pathsPath = baseDir.resolve(PATHS_FILE);
        if (Files.exists(pathsPath)) {
            List<ExploredPath> list = MAPPER.readValue(
                    Files.readAllBytes(pathsPath),
                    new TypeReference<List<ExploredPath>>() {});
            for (ExploredPath p : list) archive.pathsById.put(p.id(), p);
        }

        Path sqlPath = baseDir.resolve(CAPTURED_SQL_FILE);
        if (Files.exists(sqlPath)) {
            List<CapturedSql> list = MAPPER.readValue(
                    Files.readAllBytes(sqlPath),
                    new TypeReference<List<CapturedSql>>() {});
            for (CapturedSql sql : list) {
                archive.capturedSqlByPath
                        .computeIfAbsent(sql.pathId(), k -> new ArrayList<>())
                        .add(sql);
            }
        }

        Path httpPath = baseDir.resolve(CAPTURED_HTTP_FILE);
        if (Files.exists(httpPath)) {
            List<CapturedHttpCall> list = MAPPER.readValue(
                    Files.readAllBytes(httpPath),
                    new TypeReference<List<CapturedHttpCall>>() {});
            for (CapturedHttpCall call : list) {
                archive.capturedHttpByPath
                        .computeIfAbsent(call.pathId(), k -> new ArrayList<>())
                        .add(call);
            }
        }
        return archive;
    }

    public void save() throws IOException {
        Files.createDirectories(baseDir);
        Files.writeString(baseDir.resolve(ENDPOINTS_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        new ArrayList<>(endpoints.values())));
        Files.writeString(baseDir.resolve(PATHS_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        new ArrayList<>(pathsById.values())));
        Files.writeString(baseDir.resolve(CAPTURED_SQL_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        capturedSqlByPath.values().stream().flatMap(List::stream).collect(Collectors.toList())));
        Files.writeString(baseDir.resolve(CAPTURED_HTTP_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        capturedHttpByPath.values().stream().flatMap(List::stream).collect(Collectors.toList())));
    }

    // === Endpoint ===

    public void addEndpoint(Endpoint e) {
        endpoints.put(e.id(), e);
    }

    public Optional<Endpoint> findEndpoint(String id) {
        return Optional.ofNullable(endpoints.get(id));
    }

    public List<Endpoint> endpoints() {
        return List.copyOf(endpoints.values());
    }

    // === ExploredPath ===

    public void addExploredPath(ExploredPath p) {
        pathsById.put(p.id(), p);
    }

    public Optional<ExploredPath> findPath(String id) {
        return Optional.ofNullable(pathsById.get(id));
    }

    public List<ExploredPath> pathsByEndpoint(String endpointId) {
        return pathsById.values().stream()
                .filter(p -> p.endpointId().equals(endpointId))
                .collect(Collectors.toList());
    }

    public List<ExploredPath> allPaths() {
        return List.copyOf(pathsById.values());
    }

    // === CapturedSql ===

    public void addCapturedSql(CapturedSql sql) {
        capturedSqlByPath
                .computeIfAbsent(sql.pathId(), k -> new ArrayList<>())
                .add(sql);
    }

    public List<CapturedSql> capturedSqlByPath(String pathId) {
        return capturedSqlByPath.getOrDefault(pathId, List.of());
    }

    public Map<String, List<CapturedSql>> allCapturedSql() {
        return Map.copyOf(capturedSqlByPath);
    }

    // === CapturedHttpCall (Phase 2) ===

    public void addCapturedHttpCall(CapturedHttpCall call) {
        capturedHttpByPath
                .computeIfAbsent(call.pathId(), k -> new ArrayList<>())
                .add(call);
    }

    public List<CapturedHttpCall> capturedHttpByPath(String pathId) {
        return capturedHttpByPath.getOrDefault(pathId, List.of());
    }

    public Map<String, List<CapturedHttpCall>> allCapturedHttp() {
        return Map.copyOf(capturedHttpByPath);
    }
}
