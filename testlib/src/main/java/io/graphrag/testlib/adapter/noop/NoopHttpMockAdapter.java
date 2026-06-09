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
        return scopeTestId -> {
        };
    }
}
