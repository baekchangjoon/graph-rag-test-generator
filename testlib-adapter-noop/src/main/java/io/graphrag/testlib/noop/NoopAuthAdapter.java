package io.graphrag.testlib.noop;

import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.scope.Config;
import io.graphrag.testlib.spi.AuthAdapter;

public final class NoopAuthAdapter implements AuthAdapter {
    @Override public String name() { return "noop"; }
    @Override public AuthClient create(Config config) { return new NoopAuthClient(); }
}
