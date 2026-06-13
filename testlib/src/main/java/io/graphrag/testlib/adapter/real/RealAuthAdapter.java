package io.graphrag.testlib.adapter.real;

import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.spi.AuthAdapter;
import io.graphrag.testlib.spi.Env;

public final class RealAuthAdapter implements AuthAdapter {

    @Override
    public String name() {
        return "real";
    }

    @Override
    public AuthClient create(Env env) {
        return new JwtAuthClient(
                env.require("APP_BASE_URI"),
                env.getOrDefault("AUTH_LOGIN_PATH", "/api/auth/login"),
                env.getOrDefault("AUTH_TOKEN_FIELD", "token"));
    }
}
