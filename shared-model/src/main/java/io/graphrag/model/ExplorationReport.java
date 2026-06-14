package io.graphrag.model;

import java.util.List;
import java.util.Map;

/**
 * 탐색 종료 후 still_missing 리포트 (docs/22).
 *
 * <p>엔드포인트별 분기는 handler 메서드 기준(컨트롤러 메서드 자체 분기)이라 thin
 * 컨트롤러는 0/0이 정상이다. SUT 전체 도달 분기는 app 집계로 본다:
 * coveredAppBranches/totalAppBranches = 전 엔드포인트가 도달한 whole-app 분기 합집합 /
 * BOOT-INF/classes 전체 분기.
 */
public record ExplorationReport(
        List<EndpointExploration> endpoints,
        int coveredAppBranches,
        int totalAppBranches) {

    /**
     * solverRelevantMissed: 미커버 분기 중 handler 비교식(field op literal) 라인과
     * 겹치는 개수. 콘콜릭 복귀 트리거의 실증 데이터 (docs/decisions/explorer-engines.md).
     */
    public record EndpointExploration(
            String endpointId,
            int totalBranches,
            int coveredBranches,
            List<BranchRef> missedBranches,
            Map<String, Integer> pathsByEngine,
            int solverRelevantMissed) {
    }
}
