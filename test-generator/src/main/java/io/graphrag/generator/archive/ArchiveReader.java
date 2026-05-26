package io.graphrag.generator.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.JsonMappers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * graph-rag-builder의 GraphArchive 파일 레이아웃을 그대로 읽어서
 * {@link MultiPathSynthesisInput}으로 변환.
 *
 * <p>test-generator는 graph-rag-builder의 Spring 의존성을 끌어오지 않으려고
 * 직접 JSON 파일을 파싱한다 (shared-model 타입 사용).
 */
public final class ArchiveReader {

    private static final ObjectMapper MAPPER = JsonMappers.standard();

    private final Path baseDir;
    private final Map<String, Endpoint> endpoints = new HashMap<>();
    private final List<ExploredPath> allPaths = new ArrayList<>();
    private final Map<String, List<CapturedSql>> sqlByPath = new HashMap<>();
    private final Map<String, List<CapturedHttpCall>> httpByPath = new HashMap<>();

    private ArchiveReader(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir);
    }

    public static ArchiveReader load(Path baseDir) throws IOException {
        ArchiveReader r = new ArchiveReader(baseDir);
        r.loadJsonArray("endpoints.json", new TypeReference<List<Endpoint>>() {},
                ep -> r.endpoints.put(ep.id(), ep));
        r.loadJsonArray("paths.json", new TypeReference<List<ExploredPath>>() {},
                r.allPaths::add);
        r.loadJsonArray("captured_sql.json", new TypeReference<List<CapturedSql>>() {},
                sql -> r.sqlByPath.computeIfAbsent(sql.pathId(), k -> new ArrayList<>()).add(sql));
        r.loadJsonArray("captured_http.json", new TypeReference<List<CapturedHttpCall>>() {},
                call -> r.httpByPath.computeIfAbsent(call.pathId(), k -> new ArrayList<>()).add(call));
        return r;
    }

    private <T> void loadJsonArray(String fileName, TypeReference<List<T>> typeRef,
                                    java.util.function.Consumer<T> sink) throws IOException {
        Path file = baseDir.resolve(fileName);
        if (!Files.exists(file)) return;
        List<T> list = MAPPER.readValue(Files.readAllBytes(file), typeRef);
        for (T item : list) sink.accept(item);
    }

    public Optional<Endpoint> findEndpoint(String id) {
        return Optional.ofNullable(endpoints.get(id));
    }

    public List<Endpoint> endpoints() {
        return List.copyOf(endpoints.values());
    }

    public List<ExploredPath> pathsByEndpoint(String endpointId) {
        return allPaths.stream()
                .filter(p -> p.endpointId().equals(endpointId))
                .collect(Collectors.toList());
    }

    public List<CapturedSql> capturedSqlByPath(String pathId) {
        return sqlByPath.getOrDefault(pathId, List.of());
    }

    public List<CapturedHttpCall> capturedHttpByPath(String pathId) {
        return httpByPath.getOrDefault(pathId, List.of());
    }

    /**
     * 주어진 endpoint id에 대해 {@link MultiPathSynthesisInput} 구성.
     *
     * @throws IllegalArgumentException endpoint id가 archive에 없으면
     */
    public MultiPathSynthesisInput buildInput(String endpointId, String testPackage) {
        Endpoint ep = findEndpoint(endpointId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "endpoint not found in archive: " + endpointId));
        List<PathContext> contexts = pathsByEndpoint(endpointId).stream()
                .map(p -> new PathContext(
                        p,
                        capturedSqlByPath(p.id()),
                        capturedHttpByPath(p.id()),
                        List.of()))
                .collect(Collectors.toList());
        return new MultiPathSynthesisInput(ep, contexts, testPackage);
    }
}
