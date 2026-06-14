package io.graphrag.builder.oracle;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 입력 필드별 후보값 — InputOracle의 산출물. numeric/strings 둘 다 simple field name 기준.
 * 결정적(TreeMap/TreeSet). 여러 오라클 결과는 merge로 합집합한다.
 */
public record InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings) {

    public static InputCandidates empty() {
        return new InputCandidates(Map.of(), Map.of());
    }

    public InputCandidates merge(InputCandidates other) {
        Map<String, Set<Long>> n = new TreeMap<>();
        numeric.forEach((k, v) -> n.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        other.numeric().forEach((k, v) -> n.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        Map<String, Set<String>> s = new TreeMap<>();
        strings.forEach((k, v) -> s.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        other.strings().forEach((k, v) -> s.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        return new InputCandidates(n, s);
    }
}
