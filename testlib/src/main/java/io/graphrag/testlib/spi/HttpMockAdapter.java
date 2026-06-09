package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.HttpMockClient;

public interface HttpMockAdapter extends Adapter {
    HttpMockClient create(Env env, String testId);
}
