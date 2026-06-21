package io.graphrag.builder.index;

import io.graphrag.model.Endpoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @param validBodyEndpointIds JSON {@code @RequestBody}에 {@code @Valid}/{@code @Validated}가 붙은
 *                             엔드포인트 id 집합 — negative-validation pass 게이트(B1).
 * @param formBindingIndex     {@code @Controller} 폼 엔드포인트 id → 폼 커맨드 필드의 바인딩 종류 메타
 *                             (SCALAR/REFERENCE/NESTED). 러너/합성기가 폼 happy base 합성에 소비.
 */
public record IndexResult(
        List<Endpoint> endpoints,
        Map<String, BodyShape> bodyShapes,
        Set<String> validBodyEndpointIds,
        Map<String, List<FormFieldBinding>> formBindingIndex) {

    /** 기존 3-인자 호출부 호환: formBindingIndex 비움. */
    public IndexResult(List<Endpoint> endpoints, Map<String, BodyShape> bodyShapes,
                       Set<String> validBodyEndpointIds) {
        this(endpoints, bodyShapes, validBodyEndpointIds, Map.of());
    }

    /** 다른 IndexResult를 병합한 새 인스턴스(불변). endpoints concat, 맵 putAll, validBodyEndpointIds addAll. */
    public IndexResult merge(IndexResult other) {
        List<Endpoint> mergedEndpoints = new java.util.ArrayList<>(this.endpoints);
        mergedEndpoints.addAll(other.endpoints);
        Map<String, BodyShape> mergedShapes = new java.util.HashMap<>(this.bodyShapes);
        mergedShapes.putAll(other.bodyShapes);
        Set<String> mergedValid = new java.util.LinkedHashSet<>(this.validBodyEndpointIds);
        mergedValid.addAll(other.validBodyEndpointIds);
        Map<String, List<FormFieldBinding>> mergedForm = new java.util.HashMap<>(this.formBindingIndex);
        mergedForm.putAll(other.formBindingIndex);
        return new IndexResult(mergedEndpoints, mergedShapes, mergedValid, mergedForm);
    }
}
