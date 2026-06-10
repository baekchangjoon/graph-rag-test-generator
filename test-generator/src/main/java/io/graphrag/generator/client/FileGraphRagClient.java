package io.graphrag.generator.client;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.TableSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** graph.json 직접 조회 (도구 1 JSON 산출물이 곧 계약). */
public class FileGraphRagClient implements GraphRagClient {

    private final GraphAsset asset;

    public FileGraphRagClient(Path graphDir) {
        Path file = graphDir.resolve("graph.json");
        try {
            this.asset = Json.mapper().readValue(Files.readString(file), GraphAsset.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read graph: " + file, e);
        }
    }

    @Override
    public Endpoint endpoint(String id) {
        return asset.endpoints().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown endpoint: " + id));
    }

    @Override
    public ExploredPath path(String id) {
        return asset.paths().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown path: " + id));
    }

    @Override
    public List<ExploredPath> pathsForEndpoint(String endpointId) {
        return asset.paths().stream().filter(p -> p.endpointId().equals(endpointId)).toList();
    }

    @Override
    public List<CapturedSql> sqlForPath(String pathId) {
        return asset.sql().stream().filter(s -> s.pathId().equals(pathId)).toList();
    }

    @Override
    public List<TableSchema> tables() {
        return asset.tables();
    }
}
