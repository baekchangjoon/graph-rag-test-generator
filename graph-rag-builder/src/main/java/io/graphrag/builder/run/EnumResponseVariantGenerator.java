package io.graphrag.builder.run;

import io.graphrag.builder.index.BodyShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 응답 BodyShape의 enum 타입 필드를 변형 stub으로 갈아끼우는 변형 목록을 생성한다 (REQ-006, REQ-003).
 *
 * <p>budget 우선순위(결정적): 단일 필드 변형(필드명 정렬 × 상수 알파벳 정렬) 먼저 → 2-way 카르테시안.
 * 단계1 baseline(모든 enum=선언순 첫 상수)은 제외(이미 단계1이 측정). budget 초과분은 자르고
 * {@code dropped}로 세며 loud 로그({@code response-variant-budget-truncated})를 남긴다.
 */
public final class EnumResponseVariantGenerator {

    private static final Logger LOG = Logger.getLogger(EnumResponseVariantGenerator.class.getName());

    /** label = 정렬된 "field=CONST[,field2=CONST2]" (결정적 식별자·측정·dedupe 키). */
    public record ResponseVariant(Map<String, String> enumOverrides, String label) {
    }

    public record VariantPlan(List<ResponseVariant> kept, int dropped) {
    }

    public VariantPlan generate(BodyShape shape, Map<String, List<String>> enumConstants, int budget) {
        // 필드명 정렬 순으로 enum 필드 → 상수 목록(선언순 보존; baseline=첫 상수).
        Map<String, List<String>> enumFields = new TreeMap<>();
        for (BodyShape.BodyField field : shape.fields()) {
            List<String> consts = resolveEnumConstants(field.javaType(), enumConstants);
            if (consts != null && !consts.isEmpty()) {
                enumFields.put(field.name(), consts);
            }
        }

        List<ResponseVariant> all = new ArrayList<>();
        // 1. 단일 필드 변형: 필드명 정렬, 상수 알파벳 정렬, baseline(선언순 첫 상수) 제외.
        for (Map.Entry<String, List<String>> entry : enumFields.entrySet()) {
            String field = entry.getKey();
            String baseline = entry.getValue().get(0);
            for (String constant : sortedConstants(entry.getValue())) {
                if (constant.equals(baseline)) {
                    continue;
                }
                all.add(variant(Map.of(field, constant)));
            }
        }

        // 2. 2-way 카르테시안: 필드 쌍 정렬, 각 필드 non-baseline 상수 알파벳 정렬.
        List<String> fieldNames = new ArrayList<>(enumFields.keySet());
        for (int i = 0; i < fieldNames.size(); i++) {
            for (int j = i + 1; j < fieldNames.size(); j++) {
                String fa = fieldNames.get(i);
                String fb = fieldNames.get(j);
                String baseA = enumFields.get(fa).get(0);
                String baseB = enumFields.get(fb).get(0);
                for (String ca : sortedConstants(enumFields.get(fa))) {
                    if (ca.equals(baseA)) {
                        continue;
                    }
                    for (String cb : sortedConstants(enumFields.get(fb))) {
                        if (cb.equals(baseB)) {
                            continue;
                        }
                        Map<String, String> overrides = new LinkedHashMap<>();
                        overrides.put(fa, ca);
                        overrides.put(fb, cb);
                        all.add(variant(overrides));
                    }
                }
            }
        }

        int dropped = Math.max(0, all.size() - budget);
        List<ResponseVariant> kept = all.size() > budget ? all.subList(0, budget) : all;
        if (dropped > 0) {
            LOG.warning("response-variant-budget-truncated: shape=" + shape.javaType()
                    + " kept=" + kept.size() + " dropped=" + dropped);
        }
        return new VariantPlan(new ArrayList<>(kept), dropped);
    }

    /** label은 필드명 정렬, 그 결과 enumOverrides도 정렬 키로 재구성(결정적). */
    private static ResponseVariant variant(Map<String, String> overrides) {
        Map<String, String> sorted = new TreeMap<>(overrides);
        String label = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return new ResponseVariant(sorted, label);
    }

    private static List<String> sortedConstants(List<String> consts) {
        return consts.stream().sorted().toList();
    }

    /** enum 상수 해석: FQN 직접 매칭, 없으면 simple-name 폴백. (ShapeJsonSynthesizer와 동일 규칙.) */
    private static List<String> resolveEnumConstants(String javaType, Map<String, List<String>> enumConstants) {
        List<String> direct = enumConstants.get(javaType);
        if (direct != null) {
            return direct;
        }
        String simple = javaType.substring(javaType.lastIndexOf('.') + 1);
        return enumConstants.entrySet().stream()
                .filter(e -> e.getKey().substring(e.getKey().lastIndexOf('.') + 1).equals(simple))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
}
