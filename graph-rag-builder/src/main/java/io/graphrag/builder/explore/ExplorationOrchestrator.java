package io.graphrag.builder.explore;

import io.graphrag.builder.oracle.ResponseClassifier;
import io.graphrag.builder.oracle.StatusOnlyClassifier;
import io.graphrag.model.BranchRef;
import io.graphrag.model.Outcome;

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
    private final ResponseClassifier classifier;

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests) {
        this(engines, totalBudgetRequests, Duration.ofMinutes(5), new StatusOnlyClassifier());
    }

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests,
                                   ResponseClassifier classifier) {
        this(engines, totalBudgetRequests, Duration.ofMinutes(5), classifier);
    }

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests,
                                   Duration maxDuration) {
        this(engines, totalBudgetRequests, maxDuration, new StatusOnlyClassifier());
    }

    public ExplorationOrchestrator(List<PathExplorer> engines, int totalBudgetRequests,
                                   Duration maxDuration, ResponseClassifier classifier) {
        this.engines = engines;
        this.totalBudgetRequests = totalBudgetRequests;
        this.maxDuration = maxDuration;
        this.classifier = classifier;
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
                // path 식별 = (분류 kind) + status + coverage 지문. 지문은 probe 단위(arm-accurate)라
                // 같은 라인의 다른 arm(예: score==42 true vs false)을 연 입력이 distinct path로 보존된다.
                // 지문이 없으면(테스트 fake 등) 분기집합으로 폴백. kind를 prefix해 genuine-200(SUCCESS)과
                // enveloped-200(FAILURE)이 동일 coverage라도 collapse되지 않게 한다.
                int status = input.outcome().status();
                Outcome outcome = classifier.classify(status, input.outcome().response());
                String cov = input.outcome().coverageKey();
                String key = outcome.kind() + ":" + status + ":" + (cov != null ? cov : sorted.toString());
                candidates.putIfAbsent(key, new Proto(input, sorted, engine.name(), outcome));
            }
            if (remaining <= 0) {
                break;
            }
        }
        return toOutcome(target, known, candidates);
    }

    private record Proto(ExplorationResult.ExploredInput input, List<BranchRef> branches,
                         String engine, Outcome outcome) {
    }

    private ExplorationOutcome toOutcome(EndpointTarget target, KnownCoverage known,
                                         Map<String, Proto> candidates) {
        List<PathCandidate> paths = new ArrayList<>();
        Map<String, Integer> seqByStatusKind = new LinkedHashMap<>();
        Map<String, Integer> pathsByEngine = new LinkedHashMap<>();
        for (Proto proto : candidates.values()) {
            int status = proto.input().outcome().status();
            Outcome outcome = proto.outcome();
            // FAILURE면 의미상 status를 path-id에 박아(엔벨로프-200 → -s200e404-) genuine 200과 분리한다.
            String statusSeg = outcome.kind() == Outcome.Kind.FAILURE
                    ? "s" + status + "e" + outcome.semanticStatus()
                    : "s" + status;
            int seq = seqByStatusKind.merge(statusSeg, 1, Integer::sum);
            String pathId = target.endpoint().id() + "-" + statusSeg + "-" + seq;
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
