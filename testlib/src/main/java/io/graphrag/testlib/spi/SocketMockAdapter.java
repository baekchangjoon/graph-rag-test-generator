package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.SocketMockClient;

public interface SocketMockAdapter extends Adapter {
    SocketMockClient create(Env env, String testId);
}
