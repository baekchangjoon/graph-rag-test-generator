package io.graphrag.testlib.adapter.wiremock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.graphrag.model.Json;
import io.graphrag.testlib.api.HttpMockClient;
import io.graphrag.testlib.api.HttpStubBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * WireMock admin REST를 직접 호출하는 어댑터 (외부 라이브러리 의존 없음).
 * 스텁에 testId metadata를 남겨 scope 단위로 제거한다.
 */
public final class WireMockHttpMockClient implements HttpMockClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String adminBaseUrl;
    private final String scopeTestId;
    private final HttpClient http = HttpClient.newHttpClient();

    WireMockHttpMockClient(String adminBaseUrl, String scopeTestId) {
        this.adminBaseUrl = adminBaseUrl.replaceAll("/$", "");
        this.scopeTestId = scopeTestId;
    }

    @Override
    public HttpStubBuilder stub(String method, String urlPath) {
        return new Builder(method, urlPath);
    }

    @Override
    public void removeAllForScope(String testId) {
        ObjectNode matcher = Json.mapper().createObjectNode();
        ObjectNode byPath = matcher.putObject("matchesJsonPath");
        byPath.put("expression", "$.graphragTestId");
        byPath.put("equalTo", testId);
        post("/mappings/remove-by-metadata", matcher.toString());
    }

    private void post(String path, String body) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(adminBaseUrl + path))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("wiremock admin " + path + " failed: "
                        + response.statusCode() + " " + response.body());
            }
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("wiremock admin unreachable: " + adminBaseUrl + path, e);
        }
    }

    private final class Builder implements HttpStubBuilder {

        private final ObjectNode mapping = Json.mapper().createObjectNode();
        private final ObjectNode request;

        Builder(String method, String urlPath) {
            this.request = mapping.putObject("request");
            request.put("method", method);
            request.put("urlPath", urlPath);
            // scope 단위 제거용 — baggage 매칭 여부와 무관하게 항상 기록
            mapping.putObject("metadata").put("graphragTestId", scopeTestId);
        }

        @Override
        public HttpStubBuilder withQueryParam(String name, String value) {
            request.withObjectProperty("queryParameters").putObject(name).put("equalTo", value);
            return this;
        }

        @Override
        public HttpStubBuilder withBaggageTestId(String testId) {
            request.withObjectProperty("headers").putObject("baggage")
                    .put("contains", "test-id=" + testId);
            return this;
        }

        @Override
        public HttpStubBuilder respondJson(int status, String body) {
            ObjectNode response = mapping.putObject("response");
            response.put("status", status);
            response.putObject("headers").put("Content-Type", "application/json");
            response.put("body", body);
            return this;
        }

        @Override
        public void register() {
            post("/mappings", mapping.toString());
        }
    }
}
