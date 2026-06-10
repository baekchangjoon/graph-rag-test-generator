package io.graphrag.builder.coverage;

import io.graphrag.model.BranchRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 분기 커버리지 스냅샷.
 * JaCoCo는 라인별 분기 "개수"만 주므로 (class, method, line, k<covered) 키로
 * 단조 증가 집합을 구성한다 — novelty 판정에 충분하고 결정적이다.
 */
public record BranchCoverage(Set<BranchRef> covered, Set<BranchRef> missed, int totalBranches) {

    /** other에 없던 새로 커버된 분기. */
    public List<BranchRef> newlyCoveredAgainst(Set<BranchRef> other) {
        Set<BranchRef> fresh = new LinkedHashSet<>(covered);
        fresh.removeAll(other);
        return List.copyOf(fresh);
    }
}
