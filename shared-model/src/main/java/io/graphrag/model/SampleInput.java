package io.graphrag.model;

import java.util.Map;
import java.util.Objects;

/**
 * {@link ExploredPath} 캡처 시 실제로 사용된 입력.
 *
 * @param body 요청 body. JSON 객체면 Map, 배열이면 List, primitive면 String/Number 등. 없으면 null.
 */
public record SampleInput(
        Map<String, String> headers,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Object body) {

    public SampleInput {
        headers = Map.copyOf(Objects.requireNonNullElse(headers, Map.of()));
        pathParams = Map.copyOf(Objects.requireNonNullElse(pathParams, Map.of()));
        queryParams = Map.copyOf(Objects.requireNonNullElse(queryParams, Map.of()));
    }
}
