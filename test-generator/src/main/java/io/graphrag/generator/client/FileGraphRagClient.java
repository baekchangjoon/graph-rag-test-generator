package io.graphrag.generator.client;

import io.graphrag.model.CapturedSql;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;
import io.graphrag.model.RequiredSeed;
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
    public List<io.graphrag.model.CapturedHttpCall> httpCallsForPath(String pathId) {
        return asset.httpCalls().stream().filter(c -> c.pathId().equals(pathId)).toList();
    }

    @Override
    public boolean hasWsEndpoint(String id) {
        return asset.wsEndpoints().stream().anyMatch(w -> w.id().equals(id));
    }

    @Override
    public io.graphrag.model.WsEndpoint wsEndpoint(String id) {
        return asset.wsEndpoints().stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown ws endpoint: " + id));
    }

    @Override
    public List<io.graphrag.model.WsExchange> wsExchangesFor(String wsEndpointId) {
        return asset.wsExchanges().stream()
                .filter(x -> x.wsEndpointId().equals(wsEndpointId)).toList();
    }

    @Override
    public io.graphrag.model.WsExchange wsExchange(String exchangeId) {
        return asset.wsExchanges().stream().filter(x -> x.id().equals(exchangeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown ws exchange: " + exchangeId));
    }

    @Override
    public boolean hasKafkaConsumer(String id) {
        return asset.kafkaConsumers().stream().anyMatch(c -> c.id().equals(id));
    }

    @Override
    public io.graphrag.model.KafkaConsumer kafkaConsumer(String id) {
        return asset.kafkaConsumers().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown kafka consumer: " + id));
    }

    @Override
    public List<io.graphrag.model.KafkaExchange> kafkaExchangesFor(String consumerId) {
        return asset.kafkaExchanges().stream()
                .filter(x -> x.kafkaConsumerId().equals(consumerId)).toList();
    }

    @Override
    public List<TableSchema> tables() {
        return asset.tables();
    }

    @Override
    public List<RequiredSeed> seedsForPath(String pathId) {
        // 2xx path가 없던 read 엔드포인트의 시드는 pathId가 null(어느 path에도 미연결)일 수 있다
        return asset.seeds().stream()
                .filter(s -> java.util.Objects.equals(s.pathId(), pathId))
                .toList();
    }

    @Override
    public List<io.graphrag.model.CapturedEventEmit> capturedEventEmitsForPath(String pathId) {
        return asset.capturedEventEmits().stream().filter(e -> e.pathId().equals(pathId)).toList();
    }
}
