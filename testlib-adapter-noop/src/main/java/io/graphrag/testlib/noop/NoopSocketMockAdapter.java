package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.SocketMockClient;
import io.graphrag.testlib.scope.Config;
import io.graphrag.testlib.spi.SocketMockAdapter;

public final class NoopSocketMockAdapter implements SocketMockAdapter {
    @Override public String name() { return "noop"; }
    @Override public SocketMockClient create(Config config) { return new NoopSocketMockClient(); }
}
