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
        List<String> coveredAppClasses,
        List<UnsupportedShape> unsupportedShapes) {

    /** compact constructor: null-guard unsupportedShapes. */
    public ExplorationReport {
        unsupportedShapes = unsupportedShapes == null ? List.of() : unsupportedShapes;
    }

    /**
     * 4-argument backward-compat constructor (no unsupportedShapes).
     * 기존 호출자는 unsupportedShapes=[] 기본값을 얻는다.
     */
    public ExplorationReport(List<EndpointExploration> endpoints,
                             int coveredAppBranches,
                             int totalAppBranches,
                             List<String> coveredAppClasses) {
        this(endpoints, coveredAppBranches, totalAppBranches, coveredAppClasses, List.of());
    }

    /**
     * REQ-008: 타입 레벨 실패로 인해 건너뛴 엔드포인트를 기록하는 loud-failure 채널.
     * DroppedPath(HTTP 상태 기반)와 분리된 독립 레코드.
     */
    public record UnsupportedShape(String endpointId, String typeFqn, String reason) {}

    /**
     * solverRelevantMissed: 미커버 분기 중 handler 비교식(field op literal) 라인과
     * 겹치는 개수. 콘콜릭 복귀 트리거의 실증 데이터 (docs/decisions/explorer-engines.md).
     *
     * <p>noHappyPathReason: 탐색된 경로가 전부 FAILURE(에러 엔벨로프 포함)일 때 사유 문자열을 기록한다.
     * SUCCESS 경로가 하나라도 있거나 탐색 경로 자체가 없으면 null.
     */
    public record EndpointExploration(
            String endpointId,
            int totalBranches,
            int coveredBranches,
            List<BranchRef> missedBranches,
            Map<String, Integer> pathsByEngine,
            int solverRelevantMissed,
            List<DroppedPath> droppedPaths,
            String noHappyPathReason) {

        /**
         * 7-argument backward-compat constructor (no noHappyPathReason).
         * droppedPaths를 포함하는 기존 호출자는 noHappyPathReason=null 기본값을 얻는다.
         */
        public EndpointExploration(String endpointId, int totalBranches, int coveredBranches,
                                   List<BranchRef> missedBranches,
                                   Map<String, Integer> pathsByEngine,
                                   int solverRelevantMissed,
                                   List<DroppedPath> droppedPaths) {
            this(endpointId, totalBranches, coveredBranches, missedBranches,
                    pathsByEngine, solverRelevantMissed, droppedPaths, null);
        }

        /**
         * 6-argument backward-compat constructor (no droppedPaths — legacy).
         * 기존 호출자(BuilderCli, 테스트 등)는 이 생성자를 통해 droppedPaths=[], noHappyPathReason=null 기본값을 얻는다.
         */
        public EndpointExploration(String endpointId, int totalBranches, int coveredBranches,
                                   List<BranchRef> missedBranches,
                                   Map<String, Integer> pathsByEngine,
                                   int solverRelevantMissed) {
            this(endpointId, totalBranches, coveredBranches, missedBranches,
                    pathsByEngine, solverRelevantMissed, List.of(), null);
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
