package io.graphrag.model;

import java.util.List;

/** 도구 1의 산출물 전체. Phase 0은 단일 JSON 파일로 영속. */
public record GraphAsset(
        String sutId,
        String commitSha,
        List<Endpoint> endpoints,
        List<ExploredPath> paths,
        List<CapturedSql> sql,
        List<TableSchema> tables) {
}
