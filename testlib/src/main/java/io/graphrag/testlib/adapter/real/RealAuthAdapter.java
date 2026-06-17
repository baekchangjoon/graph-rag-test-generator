package io.graphrag.testlib.adapter.real;

import io.graphrag.model.RequestHeaders;
import io.graphrag.testlib.api.AuthClient;
import io.graphrag.testlib.spi.AuthAdapter;
import io.graphrag.testlib.spi.Env;

import java.util.List;

public final class RealAuthAdapter implements AuthAdapter {

    @Override
    public String name() {
        return "real";
    }

    @Override
    public AuthClient create(Env env) {
        String headerLines = env.getOrDefault("REQUEST_HEADERS", "");
        RequestHeaders requestHeaders = headerLines.isBlank()
                ? RequestHeaders.empty()
                : RequestHeaders.parse(List.of(headerLines.split("\\R")),
                        env.get("REQUEST_HEADERS_ON_LOGIN") != null);
        return new JwtAuthClient(
                env.require("APP_BASE_URI"),
                env.getOrDefault("AUTH_LOGIN_PATH", "/api/auth/login"),
                env.getOrDefault("AUTH_TOKEN_FIELD", "token"),
                requestHeaders);
    }
}
