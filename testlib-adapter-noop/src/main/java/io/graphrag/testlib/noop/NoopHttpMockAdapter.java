package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.scope.Config;
import io.graphrag.testlib.spi.HttpMockAdapter;

public final class NoopHttpMockAdapter implements HttpMockAdapter {
    @Override public String name() { return "noop"; }
    @Override public HttpMockClient create(Config config) { return new NoopHttpMockClient(); }
}
