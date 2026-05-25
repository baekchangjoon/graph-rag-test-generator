package io.graphrag.model;

/**
 * 외부 HTTP 호출을 발행한 클라이언트 라이브러리 종류.
 *
 * <p>도구 2의 mock 합성 시 적절한 stub 코드를 선택하는 데 사용.
 */
public enum HttpClientType {
    REST_TEMPLATE,
    WEBCLIENT,
    FEIGN,
    OKHTTP,
    OTHER
}
