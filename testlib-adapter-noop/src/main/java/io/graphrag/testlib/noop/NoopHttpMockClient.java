package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.HttpMockClient;

import java.util.UUID;

/**
 * 외부 HTTP 의존이 없는 환경(Phase 0 등)을 위한 no-op 구현.
 */
public final class NoopHttpMockClient implements HttpMockClient {

    @Override
    public StubBuilder stub(String urlPattern) {
        return new NoopStubBuilder();
    }

    @Override
    public void removeAllForScope(String testId) { /* nothing to remove */ }

    private static final class NoopStubBuilder implements StubBuilder {
        @Override public StubBuilder method(String httpMethod) { return this; }
        @Override public StubBuilder withQueryParam(String name, String expectedValue) { return this; }
        @Override public StubBuilder withHeader(String name, String expectedValue) { return this; }
        @Override public StubBuilder withBaggage(String key, String value) { return this; }
        @Override public StubBuilder respondJson(String json) { return this; }
        @Override public StubBuilder respondStatus(int statusCode) { return this; }
        @Override public String register() { return "noop-" + UUID.randomUUID(); }
    }
}
