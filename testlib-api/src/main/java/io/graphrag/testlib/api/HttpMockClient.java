package io.graphrag.testlib.api;

/**
 * HTTP mock 서비스(WireMock 등)에 stub을 등록하는 어댑터의 안정 인터페이스.
 *
 * <p>생성된 테스트 코드가 직접 사용. 백엔드 mock 서비스가 교체되어도 이 인터페이스는 유지된다.
 *
 * <p>격리는 baggage 매칭으로: {@link StubBuilder#withBaggage(String, String)}.
 */
public interface HttpMockClient {

    /**
     * 새 stub builder. {@code register()} 호출 시 mock 서비스에 등록됨.
     */
    StubBuilder stub(String urlPattern);

    /**
     * 해당 testId의 모든 stub 제거 (cleanup용).
     */
    void removeAllForScope(String testId);

    /** Builder API. 메소드 체인으로 stub 정의. */
    interface StubBuilder {
        StubBuilder method(String httpMethod);
        StubBuilder withQueryParam(String name, String expectedValue);
        StubBuilder withHeader(String name, String expectedValue);
        /** OTEL baggage 헤더 매칭. 격리를 위한 핵심 메소드. */
        StubBuilder withBaggage(String key, String value);
        StubBuilder respondJson(String json);
        StubBuilder respondStatus(int statusCode);
        /** 등록 + 발급된 stub id 반환 */
        String register();
    }
}
