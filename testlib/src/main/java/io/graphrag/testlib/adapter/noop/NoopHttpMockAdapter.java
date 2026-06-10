package io.graphrag.testlib.adapter.noop;

import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.HttpMockAdapter;

public final class NoopHttpMockAdapter implements HttpMockAdapter {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public HttpMockClient create(Env env, String testId) {
        return new HttpMockClient() {
            @Override
            public io.graphrag.testlib.api.HttpStubBuilder stub(String method, String urlPath) {
                return new io.graphrag.testlib.api.HttpStubBuilder() {
                    @Override
                    public io.graphrag.testlib.api.HttpStubBuilder withQueryParam(String name, String value) {
                        return this;
                    }

                    @Override
                    public io.graphrag.testlib.api.HttpStubBuilder withBaggageTestId(String id) {
                        return this;
                    }

                    @Override
                    public io.graphrag.testlib.api.HttpStubBuilder respondJson(int status, String body) {
                        return this;
                    }

                    @Override
                    public void register() {
                    }
                };
            }

            @Override
            public void removeAllForScope(String scopeTestId) {
            }
        };
    }
}
