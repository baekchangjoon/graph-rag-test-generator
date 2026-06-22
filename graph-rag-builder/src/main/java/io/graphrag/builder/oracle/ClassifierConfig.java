package io.graphrag.builder.oracle;

import java.util.List;
import java.util.Map;

/** CLI 옵션에서 파싱한 ResponseClassifier 설정. */
public record ClassifierConfig(
        List<String> errorWhenPresent,
        String semanticStatusField,
        String errorDetailField,
        String errorDetailContains) {

    public static ClassifierConfig from(Map<String, String> opts) {
        String when = opts.get("--error-when-present");
        List<String> fields = (when == null || when.isBlank())
                ? List.of()
                : java.util.Arrays.stream(when.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .toList();
        String statusField = opts.getOrDefault("--semantic-status-field", "errorCode");
        return new ClassifierConfig(fields, statusField,
                opts.get("--error-detail-field"), opts.get("--error-detail-contains"));
    }

    public ResponseClassifier toClassifier() {
        return errorWhenPresent.isEmpty()
                ? new StatusOnlyClassifier()
                : new ErrorEnvelopeClassifier(errorWhenPresent, semanticStatusField);
    }
}
