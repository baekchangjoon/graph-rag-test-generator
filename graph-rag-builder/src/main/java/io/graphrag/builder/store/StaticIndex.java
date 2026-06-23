package io.graphrag.builder.store;

import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.KafkaIndexResult;
import io.graphrag.builder.index.WsIndexResult;
import io.graphrag.model.MapperStatement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 직렬화 가능한 정적 인덱싱 산출물 전체 묶음(whole-result 캐시 단위). */
public record StaticIndex(
        IndexResult index,
        WsIndexResult ws,
        KafkaIndexResult kafka,
        List<MapperStatement> mappers,
        List<Set<String>> responseDtoFieldSets,
        Map<String, List<String>> enumConstants,
        List<io.graphrag.builder.index.ExternalCallSite> callSites) {

    /** compact: null-guard callSites (레거시 캐시 호환). */
    public StaticIndex {
        callSites = callSites == null ? List.of() : callSites;
    }

    /** 6-arg 레거시 호환 (callSites 없음 → 빈 리스트). */
    public StaticIndex(IndexResult index, WsIndexResult ws, KafkaIndexResult kafka,
            List<MapperStatement> mappers, List<Set<String>> responseDtoFieldSets,
            Map<String, List<String>> enumConstants) {
        this(index, ws, kafka, mappers, responseDtoFieldSets, enumConstants, List.of());
    }
}
