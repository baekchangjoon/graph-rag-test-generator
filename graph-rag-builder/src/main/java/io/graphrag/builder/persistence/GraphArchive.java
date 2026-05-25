package io.graphrag.builder.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
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
 * Phase 0 단순 파일 기반 그래프 영속. JSON 파일들로 저장.
 *
 * <p>Phase 1+에서는 Neo4j 등 그래프 저장소로 교체. 인터페이스는 동일 유지하도록 추후 SPI 도입.
 *
 * <p>파일 레이아웃:
 * <pre>
 * {baseDir}/endpoints.json         — List&lt;Endpoint&gt;
 * {baseDir}/captured_sql.json      — List&lt;CapturedSql&gt;
 * </pre>
 */
public final class GraphArchive {

    private static final String ENDPOINTS_FILE = "endpoints.json";
    private static final String CAPTURED_SQL_FILE = "captured_sql.json";

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final Path baseDir;
    private final ConcurrentMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<CapturedSql>> capturedSqlByPath = new ConcurrentHashMap<>();

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
        return archive;
    }

    public void save() throws IOException {
        Files.createDirectories(baseDir);
        Files.writeString(baseDir.resolve(ENDPOINTS_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                        new ArrayList<>(endpoints.values())));
        List<CapturedSql> allSql = capturedSqlByPath.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        Files.writeString(baseDir.resolve(CAPTURED_SQL_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(allSql));
    }

    public void addEndpoint(Endpoint e) {
        endpoints.put(e.id(), e);
    }

    public Optional<Endpoint> findEndpoint(String id) {
        return Optional.ofNullable(endpoints.get(id));
    }

    public List<Endpoint> endpoints() {
        return List.copyOf(endpoints.values());
    }

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
}
