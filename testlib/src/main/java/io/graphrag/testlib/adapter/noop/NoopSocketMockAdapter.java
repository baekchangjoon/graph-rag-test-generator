package io.graphrag.testlib.adapter.noop;

import io.graphrag.testlib.api.SocketMockClient;
import io.graphrag.testlib.spi.Env;
import io.graphrag.testlib.spi.SocketMockAdapter;

public final class NoopSocketMockAdapter implements SocketMockAdapter {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public SocketMockClient create(Env env, String testId) {
        return sessionTestId -> {
        };
    }
}
