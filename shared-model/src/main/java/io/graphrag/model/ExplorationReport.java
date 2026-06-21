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
 *
 * <p>coveredAppClasses = ≥1 분기가 covered된 app 클래스 FQN(정렬). HTTP 탐색뿐 아니라
 * Kafka consumer/WS 핸들러 실행 커버까지 포함된다(전 루프 종료 후 runWideExec 집계 기준).
 */
public record ExplorationReport(
        List<EndpointExploration> endpoints,
        int coveredAppBranches,
        int totalAppBranches,
        List<String> coveredAppClasses) {

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
            int solverRelevantMissed,
            List<DroppedPath> droppedPaths) {

        /**
         * 6-argument backward-compat constructor (no droppedPaths — legacy).
         * 기존 호출자(BuilderCli, 테스트 등)는 이 생성자를 통해 droppedPaths=[] 기본값을 얻는다.
         */
        public EndpointExploration(String endpointId, int totalBranches, int coveredBranches,
                                   List<BranchRef> missedBranches,
                                   Map<String, Integer> pathsByEngine,
                                   int solverRelevantMissed) {
            this(endpointId, totalBranches, coveredBranches, missedBranches,
                    pathsByEngine, solverRelevantMissed, List.of());
        }

        /** compact canonical constructor: null-guard droppedPaths. */
        public EndpointExploration {
            droppedPaths = droppedPaths == null ? List.of() : droppedPaths;
        }
    }

    /**
     * REQ-015: 재현 불가로 억제된 non-2xx 경로 기록.
     * capturedStatus = 탐색 중 관측된 상태, replayStatus = 클린 DB + 선언 시드 재실행 상태.
     * 두 값이 다를 때만 기록된다(억제 이유 = "status_mismatch").
     */
    public record DroppedPath(
            String endpointId,
            String pathId,
            int capturedStatus,
            int replayStatus,
            String reason) {
    }
}
