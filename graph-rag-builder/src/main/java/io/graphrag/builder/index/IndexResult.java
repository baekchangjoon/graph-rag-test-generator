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

    /** 다른 IndexResult를 병합한 새 인스턴스(불변). endpoints concat, bodyShapes putAll, validBodyEndpointIds addAll. */
    public IndexResult merge(IndexResult other) {
        List<Endpoint> mergedEndpoints = new java.util.ArrayList<>(this.endpoints);
        mergedEndpoints.addAll(other.endpoints);
        Map<String, BodyShape> mergedShapes = new java.util.HashMap<>(this.bodyShapes);
        mergedShapes.putAll(other.bodyShapes);
        Set<String> mergedValid = new java.util.LinkedHashSet<>(this.validBodyEndpointIds);
        mergedValid.addAll(other.validBodyEndpointIds);
        return new IndexResult(mergedEndpoints, mergedShapes, mergedValid);
    }
}
