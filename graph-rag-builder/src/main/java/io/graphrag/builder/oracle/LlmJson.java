package io.graphrag.builder.oracle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM/CLI 응답에서 {@code {"fields":[{"field":...,"values":[...]}]}} 를 관용적으로 추출.
 * 균형 잡힌 {..} 후보만 골라 파싱하므로, CLI 래핑(claude ```json 펜스)·모델 서문·TUI 색코드
 * (kiro-cli의 ANSI 코드는 JSON 객체 바깥에만 있음)가 섞여도 안쪽의 깨끗한 객체를 얻는다.
 */
final class LlmJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {
    }

    static LlmFieldValues parseFields(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("empty LLM response");
        }
        // 1) 통째로 시도, 2) 실패 시 균형 잡힌 {..} 후보를 앞에서부터 스캔.
        for (String candidate : candidates(raw)) {
            try {
                ParsedResponse parsed = MAPPER.readValue(candidate, ParsedResponse.class);
                if (parsed.fields() != null && !parsed.fields().isEmpty()) {
                    return toFieldValues(parsed);
                }
            } catch (Exception ignore) {
                // 다음 후보 시도
            }
        }
        throw new IllegalStateException("no {\"fields\":[...]} object found in LLM response");
    }

    private static List<String> candidates(String raw) {
        List<String> out = new ArrayList<>();
        out.add(raw.trim());
        int depth = 0;
        int start = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(raw.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    private static LlmFieldValues toFieldValues(ParsedResponse parsed) {
        Map<String, List<String>> byField = new LinkedHashMap<>();
        for (FieldVals fv : parsed.fields()) {
            if (fv.field() != null && fv.values() != null && !fv.values().isEmpty()) {
                byField.put(fv.field(), new ArrayList<>(fv.values()));
            }
        }
        return new LlmFieldValues(byField);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedResponse(List<FieldVals> fields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FieldVals(String field, List<String> values) {
    }
}
