package io.graphrag.testlib.adapter.noop;

import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.spi.AuthAdapter;
import io.graphrag.testlib.spi.Env;

public final class NoopAuthAdapter implements AuthAdapter {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public AuthClient create(Env env) {
        return (username, password) -> "noop-token";
    }
}
