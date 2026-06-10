package io.graphrag.testlib.adapter.wiremock;

import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.HttpMockAdapter;

public final class WireMockHttpMockAdapter implements HttpMockAdapter {

    @Override
    public String name() {
        return "wiremock";
    }

    @Override
    public HttpMockClient create(Env env, String testId) {
        return new WireMockHttpMockClient(env.require("HTTP_MOCK_ADMIN"));
    }
}
