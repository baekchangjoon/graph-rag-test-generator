package io.graphrag.model;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        headers = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElse(headers, Map.of())));
        pathParams = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElse(pathParams, Map.of())));
        queryParams = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNullElse(queryParams, Map.of())));
    }
}
