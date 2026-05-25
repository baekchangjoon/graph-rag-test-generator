package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.scope.Config;

public interface AuthAdapter {
    String name();
    AuthClient create(Config config);
}
