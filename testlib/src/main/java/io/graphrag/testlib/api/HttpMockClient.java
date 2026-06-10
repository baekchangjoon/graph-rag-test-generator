package io.graphrag.testlib.api;

/** 외부 HTTP mock 제어. 기본 어댑터는 WireMock (docs/07). */
public interface HttpMockClient {

    HttpStubBuilder stub(String method, String urlPath);

    void removeAllForScope(String testId);
}
