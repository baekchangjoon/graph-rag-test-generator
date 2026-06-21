package io.graphrag.builder.explore;

import io.graphrag.model.BranchRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 엔진 순차 실행 + 예산 분할 + 분기 집합 dedupe (docs/05 오케스트레이션).
 * 첫 엔진에 totalBudget의 절반을 cap으로 주고, 미사용분은 다음 엔진에 양도된다.
 */
public class ExplorationOrchestrator {

    private static final Comparator<BranchRef> BRANCH_ORDER =
            Comparator.comparing(BranchRef::classFqn)
                    .thenComparing(BranchRef::method)
                    .thenComparingInt(BranchRef::line)
                    .thenComparingInt(BranchRef::branchIndex);

    private final List<PathExplorer> engines;
    private final int totalBudgetRequests;
    private final Duration maxDuration;

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests) {
        this(engines, totalBudgetRequests, Duration.ofMinutes(5));
    }

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests,
                                   Duration maxDuration) {
        this.engines = engines;
        this.totalBudgetRequests = totalBudgetRequests;
        this.maxDuration = maxDuration;
    }

    public ExplorationOutcome explore(EndpointTarget target) {
        KnownCoverage known = new KnownCoverage();
        // key = 정렬된 분기 집합 → 첫 발견 입력이 대표 (결정적)
        Map<String, Proto> candidates = new LinkedHashMap<>();
        int remaining = totalBudgetRequests;

        for (int i = 0; i < engines.size(); i++) {
            PathExplorer engine = engines.get(i);
            boolean last = i == engines.size() - 1;
            int cap = last ? remaining : Math.min(remaining, Math.max(1, totalBudgetRequests / 2));
            ExplorationBudget budget = new ExplorationBudget(cap, maxDuration);

            ExplorationResult result = engine.explore(target, budget, known);
            remaining -= budget.used();

            for (ExplorationResult.ExploredInput input : result.inputs()) {
                List<BranchRef> sorted = input.outcome().coveredBranches().stream()
                        .sorted(BRANCH_ORDER).toList();
                // path 식별 = status + coverage 지문. 지문은 probe 단위(arm-accurate)라 같은 라인의
                // 다른 arm(예: score==42 true vs false)을 연 입력이 distinct path로 보존된다.
                // 지문이 없으면(테스트 fake 등) 분기집합으로 폴백.
                String cov = input.outcome().coverageKey();
                String key = input.outcome().status() + ":" + (cov != null ? cov : sorted.toString());
                candidates.putIfAbsent(key, new Proto(input, sorted, engine.name()));
            }
            if (remaining <= 0) {
                break;
            }
        }
        return toOutcome(target, known, candidates);
    }

    private record Proto(ExplorationResult.ExploredInput input, List<BranchRef> branches,
                         String engine) {
    }

    private ExplorationOutcome toOutcome(EndpointTarget target, KnownCoverage known,
                                         Map<String, Proto> candidates) {
        List<PathCandidate> paths = new ArrayList<>();
        Map<Integer, Integer> seqByStatus = new LinkedHashMap<>();
        Map<String, Integer> pathsByEngine = new LinkedHashMap<>();
        for (Proto proto : candidates.values()) {
            int status = proto.input().outcome().status();
            int seq = seqByStatus.merge(status, 1, Integer::sum);
            String pathId = target.endpoint().id() + "-s" + status + "-" + seq;
            pathsByEngine.merge(proto.engine(), 1, Integer::sum);
            paths.add(new PathCandidate(
                    pathId,
                    proto.input().body(),
                    status,
                    proto.input().outcome().response(),
                    proto.branches(),
                    proto.engine(),
                    proto.input().outcome().logStart(),
                    proto.input().outcome().logEnd(),
                    proto.input().outcome().httpExchanges(),
                    proto.input().outcome().capturedSql(),
                    proto.input().outcome().capturedEventEmits(),
                    proto.input().outcome().kafkaTraceId(),
                    proto.input().outcome().responseHeaders()));
        }
        return new ExplorationOutcome(paths, known.covered(), pathsByEngine);
    }
}
