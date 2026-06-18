package io.graphrag.builder.store;

import io.graphrag.model.GraphAsset;
import io.graphrag.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 파티션 샤드 영속 (roadmap 6.1). 전역 사실(global.json) + 파티션별 샤드
 * (partitions/<패키지>.json)로 분할해 단일 거대 JSON 직렬화 한계를 제거한다.
 * 파티션 키 규칙은 {@link GraphPartitioner}.
 */
public class PartitionedGraphStore implements GraphStore {

    private static final String UNASSIGNED = "_unassigned";

    private final Path dir;

    public PartitionedGraphStore(Path dir) {
        this.dir = dir;
    }

    private Path partitionsDir() {
        return dir.resolve("partitions");
    }

    @Override
    public void save(GraphAsset asset) {
        Map<String, String> ownerPartition = new HashMap<>();
        asset.endpoints().forEach(e ->
                ownerPartition.put(e.id(), GraphPartitioner.partitionOf(e.handlerClass())));
        asset.wsEndpoints().forEach(w ->
                ownerPartition.put(w.id(), GraphPartitioner.partitionOf(w.handlerClass())));
        asset.paths().forEach(p ->
                ownerPartition.put(p.id(), partitionFor(ownerPartition, p.endpointId())));
        asset.wsExchanges().forEach(x ->
                ownerPartition.put(x.id(), partitionFor(ownerPartition, x.wsEndpointId())));

        Map<String, List<io.graphrag.model.Endpoint>> endpoints =
                groupBy(asset.endpoints(), e -> partitionFor(ownerPartition, e.id()));
        Map<String, List<io.graphrag.model.ExploredPath>> paths =
                groupBy(asset.paths(), p -> partitionFor(ownerPartition, p.id()));
        Map<String, List<io.graphrag.model.CapturedSql>> sql =
                groupBy(asset.sql(), s -> partitionFor(ownerPartition, s.pathId()));
        Map<String, List<io.graphrag.model.CapturedHttpCall>> httpCalls =
                groupBy(asset.httpCalls(), c -> partitionFor(ownerPartition, c.pathId()));
        Map<String, List<io.graphrag.model.WsEndpoint>> wsEndpoints =
                groupBy(asset.wsEndpoints(), w -> partitionFor(ownerPartition, w.id()));
        Map<String, List<io.graphrag.model.WsExchange>> wsExchanges =
                groupBy(asset.wsExchanges(), x -> partitionFor(ownerPartition, x.id()));
        Map<String, List<io.graphrag.model.CapturedEventEmit>> capturedEventEmits =
                groupBy(asset.capturedEventEmits(), e -> partitionFor(ownerPartition, e.pathId()));

        Map<String, GraphAsset> shards = new LinkedHashMap<>();
        Stream.of(endpoints, paths, sql, httpCalls, wsEndpoints, wsExchanges, capturedEventEmits)
                .flatMap(m -> m.keySet().stream()).sorted().distinct()
                .forEach(key -> shards.put(key, new GraphAsset(null, null,
                        endpoints.getOrDefault(key, List.of()),
                        paths.getOrDefault(key, List.of()),
                        sql.getOrDefault(key, List.of()),
                        List.of(), List.of(),
                        httpCalls.getOrDefault(key, List.of()),
                        wsEndpoints.getOrDefault(key, List.of()),
                        wsExchanges.getOrDefault(key, List.of()),
                        List.of(), List.of(),
                        List.of(),
                        capturedEventEmits.getOrDefault(key, List.of()))));

        GraphAsset global = new GraphAsset(asset.sutId(), asset.commitSha(),
                List.of(), List.of(), List.of(), asset.tables(), asset.mappers(),
                List.of(), List.of(), List.of(),
                asset.kafkaConsumers(), asset.kafkaExchanges(), asset.seeds());

        try {
            Files.createDirectories(partitionsDir());
            try (Stream<Path> stale = Files.list(partitionsDir())) {
                for (Path file : stale.filter(p -> p.toString().endsWith(".json")).toList()) {
                    Files.delete(file);
                }
            }
            writeJson(dir.resolve("global.json"), global);
            for (Map.Entry<String, GraphAsset> shard : shards.entrySet()) {
                writeJson(partitionsDir().resolve(shard.getKey() + ".json"), shard.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save partitioned graph to " + dir, e);
        }
    }

    @Override
    public GraphAsset load() {
        try {
            GraphAsset global = readJson(dir.resolve("global.json"));
            List<io.graphrag.model.Endpoint> endpoints = new ArrayList<>();
            List<io.graphrag.model.ExploredPath> paths = new ArrayList<>();
            List<io.graphrag.model.CapturedSql> sql = new ArrayList<>();
            List<io.graphrag.model.CapturedHttpCall> httpCalls = new ArrayList<>();
            List<io.graphrag.model.WsEndpoint> wsEndpoints = new ArrayList<>();
            List<io.graphrag.model.WsExchange> wsExchanges = new ArrayList<>();
            List<io.graphrag.model.CapturedEventEmit> capturedEventEmits = new ArrayList<>();
            try (Stream<Path> files = Files.list(partitionsDir())) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json"))
                        .sorted().toList()) {
                    GraphAsset shard = readJson(file);
                    endpoints.addAll(shard.endpoints());
                    paths.addAll(shard.paths());
                    sql.addAll(shard.sql());
                    httpCalls.addAll(shard.httpCalls());
                    wsEndpoints.addAll(shard.wsEndpoints());
                    wsExchanges.addAll(shard.wsExchanges());
                    capturedEventEmits.addAll(shard.capturedEventEmits());
                }
            }
            return new GraphAsset(global.sutId(), global.commitSha(), endpoints, paths,
                    sql, global.tables(), global.mappers(), httpCalls,
                    wsEndpoints, wsExchanges,
                    global.kafkaConsumers(), global.kafkaExchanges(), global.seeds(),
                    capturedEventEmits);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load partitioned graph from " + dir, e);
        }
    }

    private static String partitionFor(Map<String, String> ownerPartition, String ownerId) {
        return ownerPartition.getOrDefault(ownerId, UNASSIGNED);
    }

    private static <T> Map<String, List<T>> groupBy(List<T> items, Function<T, String> key) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        items.forEach(item -> grouped.computeIfAbsent(key.apply(item),
                k -> new ArrayList<>()).add(item));
        return grouped;
    }

    private void writeJson(Path file, GraphAsset asset) throws IOException {
        Files.writeString(file, Json.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(asset));
    }

    private GraphAsset readJson(Path file) throws IOException {
        return Json.mapper().readValue(Files.readString(file), GraphAsset.class);
    }
}
