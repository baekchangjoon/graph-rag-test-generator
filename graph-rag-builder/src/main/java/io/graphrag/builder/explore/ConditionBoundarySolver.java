package io.graphrag.builder.explore;

import io.graphrag.builder.index.ConstraintExtractor.Comparison;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * handler 비교식의 리터럴 경계를 입력값 후보로 변환한다. 각 리터럴 L → {L-1, L, L+1}.
 * 콘콜릭/SMT 대체가 아니라, 리터럴이 소스에 그대로 있는 경우의 경계값을 결정적으로 환류.
 * 정렬 컬렉션(TreeMap/TreeSet)으로 순서 고정 (docs/04 결정성).
 */
public final class ConditionBoundarySolver {

    public Map<String, Set<Long>> solve(List<Comparison> comparisons) {
        Map<String, Set<Long>> bounds = new TreeMap<>();
        for (Comparison c : comparisons) {
            Set<Long> values = bounds.computeIfAbsent(c.fieldRef(), k -> new TreeSet<>());
            values.add(c.literal() - 1);
            values.add(c.literal());
            values.add(c.literal() + 1);
        }
        return bounds;
    }
}
