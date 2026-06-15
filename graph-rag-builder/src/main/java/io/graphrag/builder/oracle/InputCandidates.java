package io.graphrag.builder.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 입력 필드별 후보값 — InputOracle의 산출물. numeric/strings 둘 다 simple field name 기준.
 * tuples: 여러 필드를 동시에 만족시켜야 하는 inter-field 가드(Stage 4)의 해 — 필드→값 배정.
 * 결정적(TreeMap/TreeSet, 정렬된 tuple). 여러 오라클 결과는 merge로 합집합한다.
 */
public record InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings,
                              List<Map<String, Long>> tuples) {

    /** 2-arg 호환 ctor: tuples 없는 오라클(StaticLiteralOracle 등)용 → 빈 tuples. */
    public InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings) {
        this(numeric, strings, List.of());
    }

    public static InputCandidates empty() {
        return new InputCandidates(Map.of(), Map.of(), List.of());
    }

    public InputCandidates merge(InputCandidates other) {
        Map<String, Set<Long>> n = new TreeMap<>();
        numeric.forEach((k, v) -> n.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        other.numeric().forEach((k, v) -> n.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        Map<String, Set<String>> s = new TreeMap<>();
        strings.forEach((k, v) -> s.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        other.strings().forEach((k, v) -> s.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        // tuples union-all, canonical-key 정렬로 결정성 보장(동일 배정은 collapse).
        TreeMap<String, Map<String, Long>> byKey = new TreeMap<>();
        for (Map<String, Long> t : tuples) {
            byKey.put(tupleKey(t), t);
        }
        for (Map<String, Long> t : other.tuples()) {
            byKey.put(tupleKey(t), t);
        }
        return new InputCandidates(n, s, new ArrayList<>(byKey.values()));
    }

    private static String tupleKey(Map<String, Long> t) {
        return new TreeMap<>(t).toString();
    }
}
