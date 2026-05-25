package io.graphrag.builder.exploration;

import java.time.Duration;

/**
 * {@link PathExplorer}의 자원 한도. orchestrator가 엔진별로 할당.
 *
 * @param maxInputs 제안할 최대 입력 수
 * @param timeLimit 탐색 작업 시간 한도 (현재 ManualPathExplorer는 즉시 반환)
 */
public record ExplorationBudget(int maxInputs, Duration timeLimit) {
    public ExplorationBudget {
        if (maxInputs < 0) throw new IllegalArgumentException("maxInputs must be >= 0");
        if (timeLimit == null) throw new IllegalArgumentException("timeLimit required");
    }
}
