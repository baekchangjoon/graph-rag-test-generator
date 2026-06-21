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
 * reals/realTuples: float/double 필드의 Real solve 해(작업 #4) — numeric/tuples(Long)와 별도 채널이라
 * 정수 경로는 무회귀. 결정적(TreeMap/TreeSet, 정렬된 tuple). 여러 오라클 결과는 merge로 합집합한다.
 */
public record InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings,
                              List<Map<String, Long>> tuples,
                              Map<String, Set<Double>> reals,
                              List<Map<String, Double>> realTuples) {

    /** 2-arg 호환 ctor: tuples/reals 없는 오라클(StaticLiteralOracle 등)용 → 빈 채널. */
    public InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings) {
        this(numeric, strings, List.of(), Map.of(), List.of());
    }

    /** 3-arg 호환 ctor: 정수 tuples만 내는 오라클용 → 빈 real 채널. */
    public InputCandidates(Map<String, Set<Long>> numeric, Map<String, Set<String>> strings,
                           List<Map<String, Long>> tuples) {
        this(numeric, strings, tuples, Map.of(), List.of());
    }

    public static InputCandidates empty() {
        return new InputCandidates(Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }

    public InputCandidates merge(InputCandidates other) {
        return new InputCandidates(
                mergeSets(numeric, other.numeric),
                mergeSets(strings, other.strings),
                mergeTuples(tuples, other.tuples, InputCandidates::longTupleKey),
                mergeSets(reals, other.reals),
                mergeTuples(realTuples, other.realTuples, InputCandidates::doubleTupleKey));
    }

    private static <V extends Comparable<V>> Map<String, Set<V>> mergeSets(
            Map<String, Set<V>> a, Map<String, Set<V>> b) {
        Map<String, Set<V>> out = new TreeMap<>();
        a.forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        b.forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        return out;
    }

    private static <V> List<Map<String, V>> mergeTuples(List<Map<String, V>> a, List<Map<String, V>> b,
                                                        java.util.function.Function<Map<String, V>, String> key) {
        // union-all, canonical-key 정렬로 결정성 보장(동일 배정은 collapse).
        TreeMap<String, Map<String, V>> byKey = new TreeMap<>();
        for (Map<String, V> t : a) {
            byKey.put(key.apply(t), t);
        }
        for (Map<String, V> t : b) {
            byKey.put(key.apply(t), t);
        }
        return new ArrayList<>(byKey.values());
    }

    private static String longTupleKey(Map<String, Long> t) {
        return new TreeMap<>(t).toString();
    }

    private static String doubleTupleKey(Map<String, Double> t) {
        return new TreeMap<>(t).toString();
    }
}
