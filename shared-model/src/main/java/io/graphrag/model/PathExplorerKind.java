package io.graphrag.model;

/**
 * 분기 탐색 엔진의 종류.
 *
 * <p>{@link ExploredPath#discoveredBy()}에서 어떤 엔진이 path를 발견했는지 추적.
 */
public enum PathExplorerKind {
    MANUAL,
    JDART,
    FUZZER,
    EVOSUITE
}
