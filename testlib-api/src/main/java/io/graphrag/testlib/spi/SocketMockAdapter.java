package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.SocketMockClient;
import io.graphrag.testlib.scope.Config;

public interface SocketMockAdapter {
    String name();
    SocketMockClient create(Config config);
}
