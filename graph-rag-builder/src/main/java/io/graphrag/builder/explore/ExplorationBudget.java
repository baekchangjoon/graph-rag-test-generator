package io.graphrag.builder.explore;

import java.time.Duration;
import java.time.Instant;

/** 엔진별 예산 (docs/05 totalBudget의 슬라이스). 요청 횟수가 주예산, 시간은 안전 cap. */
public final class ExplorationBudget {

    private final int maxRequests;
    private final Instant deadline;
    private int used;

    public ExplorationBudget(int maxRequests, Duration maxDuration) {
        this.maxRequests = maxRequests;
        this.deadline = Instant.now().plus(maxDuration);
    }

    /** 요청 1회 예산 소비. 소진/시간초과면 false. */
    public boolean tryConsume() {
        if (used >= maxRequests || Instant.now().isAfter(deadline)) {
            return false;
        }
        used++;
        return true;
    }

    public int used() {
        return used;
    }
}
