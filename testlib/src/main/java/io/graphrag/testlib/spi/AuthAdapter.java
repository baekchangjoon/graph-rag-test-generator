package io.graphrag.testlib.spi;

import io.graphrag.testlib.api.AuthClient;

public interface AuthAdapter extends Adapter {
    AuthClient create(Env env);
}
