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
        List<MapperStatement> mappers) {

    /** Phase 0 그래프(mappers 없음)와의 후방 호환. */
    public GraphAsset {
        mappers = mappers == null ? List.of() : mappers;
    }
}
