package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 리프-키(leaf-keyed) InputCandidates를 dot-path 키로 승격(lift).
 * <p>
 * concolic oracle이 단순 필드명(예: {@code min})으로 후보를 산출하지만,
 * 중첩 바디의 mutableFields는 dot-path(예: {@code range.min})다.
 * 이 클래스는 양쪽을 매칭해 후보를 올바른 경로로 전달한다.
 * <p>
 * 규칙:
 * <ul>
 *   <li>키 {@code k}가 mutableFields의 어떤 name과도 정확히 일치(평탄 필드 포함) → 그대로 유지.
 *   <li>일치하지 않고 dot-path 리프(마지막 세그먼트)와 매칭되는 경로가 있으면,
 *       각 매칭 경로에 후보 집합을 복제 발행. 원래 리프 키는 제거(mutableField가 아니므로).
 *   <li>매칭되는 경로가 없으면(기존 동작 유지) 그대로.
 *   <li>튜플(inter-field 제약): 유일-매칭 키만 승격; 다중-매칭 키는 그대로(애매성 차단).
 *   <li>결정적: TreeMap 정렬 보장.
 * </ul>
 */
public final class CandidateLifter {

    private CandidateLifter() {}

    /**
     * lift: leaf-keyed InputCandidates를 mutableFields dot-path 기준으로 재키잉.
     *
     * @param c             원본 candidates
     * @param mutableFields 이 엔드포인트의 변경 가능 필드 목록 (dot-path 포함)
     * @return 키 승격된 새 InputCandidates
     */
    public static InputCandidates lift(InputCandidates c, List<BodyShape.BodyField> mutableFields) {
        if (mutableFields == null || mutableFields.isEmpty()) {
            return c;
        }

        // mutableFields 이름 집합: 정확 일치 검사용
        Set<String> exactNames = new java.util.HashSet<>();
        // leafName -> sorted dotPaths list (다중 매칭 지원)
        Map<String, List<String>> leafToPaths = new TreeMap<>();

        for (BodyShape.BodyField field : mutableFields) {
            String name = field.name();
            exactNames.add(name);
            String leaf = leafSegment(name);
            leafToPaths.computeIfAbsent(leaf, x -> new ArrayList<>()).add(name);
        }
        // 각 list를 정렬해 결정성 보장
        leafToPaths.values().forEach(list -> java.util.Collections.sort(list));

        Map<String, Set<Long>> liftedNumeric = liftMap(c.numeric(), exactNames, leafToPaths);
        Map<String, Set<String>> liftedStrings = liftMap(c.strings(), exactNames, leafToPaths);
        Map<String, Set<Double>> liftedReals = liftMap(c.reals(), exactNames, leafToPaths);
        List<Map<String, Long>> liftedTuples = liftTupleList(c.tuples(), exactNames, leafToPaths);
        List<Map<String, Double>> liftedRealTuples = liftTupleList(c.realTuples(), exactNames, leafToPaths);

        return new InputCandidates(liftedNumeric, liftedStrings, liftedTuples, liftedReals, liftedRealTuples);
    }

    /** dot-path 마지막 세그먼트 반환 (점 없으면 그대로). */
    private static String leafSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * 단일 필드 맵 승격.
     * 정확 일치(exactNames): 그대로.
     * 리프 매칭: 각 dot-path에 복제 발행, 원본 리프 키 제거(exactNames에 없는 경우).
     * 매칭 없음: 그대로.
     */
    private static <V extends Comparable<V>> Map<String, Set<V>> liftMap(
            Map<String, Set<V>> original, Set<String> exactNames,
            Map<String, List<String>> leafToPaths) {
        Map<String, Set<V>> out = new TreeMap<>();
        for (Map.Entry<String, Set<V>> entry : original.entrySet()) {
            String key = entry.getKey();
            Set<V> value = entry.getValue();

            if (exactNames.contains(key)) {
                // 정확 일치: flat 필드 또는 dot-path 그대로
                out.computeIfAbsent(key, x -> new java.util.TreeSet<>()).addAll(value);
            } else {
                List<String> paths = leafToPaths.get(key);
                if (paths != null && !paths.isEmpty()) {
                    // 리프 매칭: 모든 매칭 경로에 발행, 원본 키 제거
                    for (String path : paths) {
                        out.computeIfAbsent(path, x -> new java.util.TreeSet<>()).addAll(value);
                    }
                } else {
                    // 매칭 없음: 그대로
                    out.computeIfAbsent(key, x -> new java.util.TreeSet<>()).addAll(value);
                }
            }
        }
        return out;
    }

    /**
     * 튜플 리스트 승격: 각 튜플 내의 키를 승격.
     * 튜플은 inter-field 제약 — 키가 다중 경로로 매핑되면 애매하므로 해당 키는 그대로 둔다(유일-매칭만 승격).
     */
    private static <V extends Comparable<V>> List<Map<String, V>> liftTupleList(
            List<Map<String, V>> tuples, Set<String> exactNames,
            Map<String, List<String>> leafToPaths) {
        List<Map<String, V>> result = new ArrayList<>();
        for (Map<String, V> tuple : tuples) {
            Map<String, V> lifted = new TreeMap<>();
            for (Map.Entry<String, V> entry : tuple.entrySet()) {
                String key = entry.getKey();
                V value = entry.getValue();

                if (exactNames.contains(key)) {
                    lifted.put(key, value);
                } else {
                    List<String> paths = leafToPaths.get(key);
                    if (paths != null && paths.size() == 1) {
                        // 유일 매칭: 승격
                        lifted.put(paths.get(0), value);
                    } else {
                        // 다중 매칭 또는 매칭 없음: 그대로
                        lifted.put(key, value);
                    }
                }
            }
            result.add(lifted);
        }
        return result;
    }
}
