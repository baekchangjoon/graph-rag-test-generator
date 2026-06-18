package io.graphrag.model;

import java.util.List;

/** 도구 1의 산출물 전체. 단일 JSON 파일로 영속 (Phase 1 결정 유지). */
public record GraphAsset(
        String sutId,
        String commitSha,
        List<Endpoint> endpoints,
        List<ExploredPath> paths,
        List<CapturedSql> sql,
        List<TableSchema> tables,
        List<MapperStatement> mappers,
        List<CapturedHttpCall> httpCalls,
        List<WsEndpoint> wsEndpoints,
        List<WsExchange> wsExchanges,
        List<KafkaConsumer> kafkaConsumers,
        List<KafkaExchange> kafkaExchanges,
        List<RequiredSeed> seeds,
        List<CapturedEventEmit> capturedEventEmits) {

    /** 이전 Phase 그래프(누락 필드)와의 후방 호환. */
    public GraphAsset {
        mappers = mappers == null ? List.of() : mappers;
        httpCalls = httpCalls == null ? List.of() : httpCalls;
        wsEndpoints = wsEndpoints == null ? List.of() : wsEndpoints;
        wsExchanges = wsExchanges == null ? List.of() : wsExchanges;
        kafkaConsumers = kafkaConsumers == null ? List.of() : kafkaConsumers;
        kafkaExchanges = kafkaExchanges == null ? List.of() : kafkaExchanges;
        seeds = seeds == null ? List.of() : seeds;
        capturedEventEmits = capturedEventEmits == null ? List.of() : capturedEventEmits;
    }

    /** 13-argument compatibility constructor */
    public GraphAsset(String sutId, String commitSha, List<Endpoint> endpoints, List<ExploredPath> paths,
                      List<CapturedSql> sql, List<TableSchema> tables, List<MapperStatement> mappers,
                      List<CapturedHttpCall> httpCalls, List<WsEndpoint> wsEndpoints, List<WsExchange> wsExchanges,
                      List<KafkaConsumer> kafkaConsumers, List<KafkaExchange> kafkaExchanges, List<RequiredSeed> seeds) {
        this(sutId, commitSha, endpoints, paths, sql, tables, mappers, httpCalls, wsEndpoints, wsExchanges,
             kafkaConsumers, kafkaExchanges, seeds, List.of());
    }
}
