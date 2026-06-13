package io.graphrag.testlib.adapter.real;

import io.graphrag.model.Json;
import io.graphrag.testlib.api.AuthClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** petclinic ApiBlackBoxTestSupport.authToken() 패턴: login 1회 + volatile 캐싱. */
public final class JwtAuthClient implements AuthClient {

    private final String baseUri;
    private final String loginPath;
    private final String tokenField;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile String cached;

    public JwtAuthClient(String baseUri, String loginPath, String tokenField) {
        this.baseUri = baseUri;
        this.loginPath = loginPath;
        this.tokenField = tokenField;
    }

    @Override
    public String login(String username, String password) {
        String local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = doLogin(username, password);
            }
            return cached;
        }
    }

    private String doLogin(String username, String password) {
        try {
            String body = Json.mapper().writeValueAsString(
                    Map.of("username", username, "password", password));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUri + loginPath))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return Json.mapper().readTree(response.body()).path(tokenField).asText();
        } catch (Exception e) {
            throw new IllegalStateException("login failed", e);
        }
    }
}
