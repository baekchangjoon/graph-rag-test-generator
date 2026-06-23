package io.graphrag.builder.oracle;

import java.util.List;
import java.util.Map;

/** LLM이 생성한 필드별 문자열 후보값 — structured 출력·캐시 직렬화 계약. */
public record LlmFieldValues(Map<String, List<String>> stringValuesByField) {
    public static LlmFieldValues empty() {
        return new LlmFieldValues(Map.of());
    }
}
