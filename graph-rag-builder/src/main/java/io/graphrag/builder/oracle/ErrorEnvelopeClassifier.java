package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;

import java.util.List;

/**
 * 200-wrapped 에러 엔벨로프를 FAILURE로 판정하는 ResponseClassifier.
 * triggerFields 중 하나라도 존재(presence) AND non-null AND non-empty면 에러로 본다 (OR).
 * statusField 값으로 semanticStatus 복원을 시도하되, 파싱 불가 시 wireStatus를 그대로 유지한다.
 */
public final class ErrorEnvelopeClassifier implements ResponseClassifier {

    private final List<String> triggerFields;
    private final String statusField;

    public ErrorEnvelopeClassifier(List<String> triggerFields, String statusField) {
        this.triggerFields = triggerFields;
        this.statusField = statusField;
    }

    @Override
    public Outcome classify(int wireStatus, JsonNode body) {
        if (body == null || !isError(body)) return Outcome.success(wireStatus);
        String text = body.hasNonNull(statusField) ? body.get(statusField).asText() : String.valueOf(wireStatus);
        int semantic = wireStatus;
        try { semantic = Integer.parseInt(text.trim()); } catch (NumberFormatException ignored) {}
        return new Outcome(Outcome.Kind.FAILURE, semantic, text, "envelope:" + statusField);
    }

    private boolean isError(JsonNode body) {
        for (String f : triggerFields) {
            JsonNode v = body.get(f);
            if (v != null && !v.isNull() && !v.asText().isEmpty()) return true;  // 존재 AND non-null AND non-empty (OR)
        }
        return false;
    }
}
