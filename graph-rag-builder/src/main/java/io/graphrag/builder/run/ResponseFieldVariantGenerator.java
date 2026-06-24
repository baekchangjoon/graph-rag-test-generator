package io.graphrag.builder.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 응답 필드별 non-baseline 후보를 변형 plan으로 만든다(REQ-008, REQ-006). 후보 출처(enum/String) 무관.
 *
 * <p>budget 우선순위(결정적): 단일 필드 변형(필드명 정렬 × 값 정렬) 먼저 → 2-way 카르테시안.
 * 호출자가 baseline 제외 완료 상태로 후보 맵을 넘긴다. budget 초과분은 자르고
 * {@code dropped}로 세며 loud 로그({@code response-variant-budget-truncated})를 남긴다.
 */
public final class ResponseFieldVariantGenerator {

    private static final Logger LOG = Logger.getLogger(ResponseFieldVariantGenerator.class.getName());

    /** label = 정렬된 "field=VAL[,field2=VAL2]" (결정적 식별자·측정·dedupe 키). */
    public record ResponseVariant(Map<String, String> overrides, String label) {
    }

    public record VariantPlan(List<ResponseVariant> kept, int dropped) {
    }

    /**
     * candidates: field → non-baseline 값 목록(호출자가 baseline 제외 완료). 결정적 정렬.
     * 빈 값 목록인 필드는 무시한다.
     */
    public VariantPlan generate(Map<String, List<String>> candidates, int budget) {
        TreeMap<String, List<String>> fields = new TreeMap<>();
        candidates.forEach((f, vals) -> {
            if (vals != null && !vals.isEmpty()) {
                fields.put(f, vals.stream().sorted().toList());
            }
        });

        List<ResponseVariant> all = new ArrayList<>();

        // 1. 단일 필드 변형(필드명 정렬 × 값 정렬).
        for (var entry : fields.entrySet()) {
            for (String val : entry.getValue()) {
                all.add(variant(Map.of(entry.getKey(), val)));
            }
        }

        // 2. 2-way 카르테시안(필드 쌍 정렬, 각 필드 값 정렬).
        List<String> names = new ArrayList<>(fields.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String fa = names.get(i);
                String fb = names.get(j);
                for (String va : fields.get(fa)) {
                    for (String vb : fields.get(fb)) {
                        Map<String, String> overrides = new LinkedHashMap<>();
                        overrides.put(fa, va);
                        overrides.put(fb, vb);
                        all.add(variant(overrides));
                    }
                }
            }
        }

        int dropped = Math.max(0, all.size() - budget);
        List<ResponseVariant> kept = all.size() > budget ? all.subList(0, budget) : all;
        if (dropped > 0) {
            LOG.warning("response-variant-budget-truncated: kept=" + kept.size() + " dropped=" + dropped);
        }
        return new VariantPlan(new ArrayList<>(kept), dropped);
    }

    /** label은 필드명 정렬, 그 결과 overrides도 정렬 키로 재구성(결정적). */
    private static ResponseVariant variant(Map<String, String> overrides) {
        TreeMap<String, String> sorted = new TreeMap<>(overrides);
        String label = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return new ResponseVariant(sorted, label);
    }
}
