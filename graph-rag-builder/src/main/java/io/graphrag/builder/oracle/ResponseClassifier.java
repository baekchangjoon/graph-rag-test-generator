package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;

/**
 * HTTP 응답을 성공/실패로 판정하는 교체가능 인터페이스.
 * 기본 구현은 {@link StatusOnlyClassifier} (HTTP 상태 코드 2xx 여부).
 */
public interface ResponseClassifier {

    Outcome classify(int wireStatus, JsonNode body);
}
