package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param validBodyEndpointIds JSON {@code @RequestBody}에 {@code @Valid}/{@code @Validated}가 붙은
 *                             엔드포인트 id 집합 — negative-validation pass 게이트(B1).
 */
public record IndexResult(
        List<Endpoint> endpoints,
        Map<String, BodyShape> bodyShapes,
        Set<String> validBodyEndpointIds) {
}
