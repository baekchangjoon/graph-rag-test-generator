package io.graphrag.builder.run;

import io.graphrag.model.Json;
import io.graphrag.model.RequestHeaders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/** 탐색 시작 전 1회 로그인 → token 캐시 (빌드 런 전체 공유). */
public final class AuthTokenProvider {

    private final String baseUri;
    private final AuthConfig config;
    private final RequestHeaders extraHeaders;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile String cached;

    public AuthTokenProvider(String baseUri, AuthConfig config, RequestHeaders extraHeaders) {
        this.baseUri = baseUri;
        this.config = config;
        this.extraHeaders = extraHeaders;
    }

    public String token() {
        String local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = login();
            }
            return cached;
        }
    }

    private String login() {
        try {
            String body = Json.mapper().writeValueAsString(java.util.Map.of(
                    "username", config.username(), "password", config.password()));
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUri + config.loginPath()))
                    .header("Content-Type", "application/json");
            if (extraHeaders.onLogin()) {
                Instant now = Instant.now();
                extraHeaders.resolved(now).forEach(req::header);
            }
            HttpResponse<String> response = http.send(
                    req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("login failed: HTTP " + response.statusCode());
            }
            String token = Json.mapper().readTree(response.body())
                    .path(config.tokenField()).asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("token field '" + config.tokenField()
                        + "' missing in login response");
            }
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("auth login failed", e);
        }
    }
}
