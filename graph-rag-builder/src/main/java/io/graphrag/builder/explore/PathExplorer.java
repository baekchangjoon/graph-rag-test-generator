package io.graphrag.builder.explore;

/** 분기 탐색 엔진 SPI (docs/05). 엔진은 known에 자신의 발견을 누적한다. */
public interface PathExplorer {

    String name();

    ExplorationResult explore(EndpointTarget target, ExplorationBudget budget, KnownCoverage known);
}
