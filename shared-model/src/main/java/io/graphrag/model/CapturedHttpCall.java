package io.graphrag.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 분석 중 캡처된 외부 HTTP 호출.
 *
 * <p>SUT가 외부 시스템에 요청을 보낸 사실. 도구 2가 WireMock stub으로 변환.
 *
 * @param urlTemplate 변수 보존된 URL ("/inventory/stock?type={type}")
 * @param urlConcrete 실 호출 URL ("/inventory/stock?type=EXPRESS")
 * @param responseFieldsReadBySut SUT가 응답에서 실제로 읽은 JSON path들. mock 응답 최소화에 사용.
 * @param targetExternalId 외부 시스템 식별자 (호스트명 또는 논리명)
 */
public record CapturedHttpCall(
        String id,
        String pathId,
        String method,
        String urlTemplate,
        String urlConcrete,
        List<Binding> urlBindings,
        Map<String, String> requestHeaders,
        Object requestBody,
        List<Binding> requestBodyBindings,
        int responseStatus,
        Object responseBodyObserved,
        List<String> responseFieldsReadBySut,
        HttpClientType clientType,
        String targetExternalId) {

    public CapturedHttpCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        Objects.requireNonNull(urlConcrete, "urlConcrete");
        Objects.requireNonNull(clientType, "clientType");
        urlBindings = List.copyOf(Objects.requireNonNullElse(urlBindings, List.of()));
        requestHeaders = Map.copyOf(Objects.requireNonNullElse(requestHeaders, Map.of()));
        requestBodyBindings = List.copyOf(Objects.requireNonNullElse(requestBodyBindings, List.of()));
        responseFieldsReadBySut = List.copyOf(Objects.requireNonNullElse(responseFieldsReadBySut, List.of()));
    }
}
