package io.graphrag.testlib.api;

/** 외부 HTTP mock 제어. Phase 0은 noop, Phase 2에서 WireMock stub 등록 API 확장. */
public interface HttpMockClient {
    void removeAllForScope(String testId);
}
