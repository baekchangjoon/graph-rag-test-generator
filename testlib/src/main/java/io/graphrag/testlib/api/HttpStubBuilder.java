package io.graphrag.testlib.api;

/** 외부 HTTP mock 스텁 1건의 빌더 (docs/04 http-mock 슬롯의 런타임 측). */
public interface HttpStubBuilder {

    HttpStubBuilder withQueryParam(String name, String value);

    /** baggage 헤더의 test-id 매칭 — 병렬 격리의 핵심 (docs/06). */
    HttpStubBuilder withBaggageTestId(String testId);

    HttpStubBuilder respondJson(int status, String body);

    void register();
}
