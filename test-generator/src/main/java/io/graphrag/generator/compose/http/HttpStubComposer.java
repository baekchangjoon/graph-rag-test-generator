package io.graphrag.generator.compose.http;

import io.graphrag.model.CapturedHttpCall;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link CapturedHttpCall} → WireMock stub 등록 Java 코드 라인.
 *
 * <p>두 가지 합성 모드:
 * <ul>
 *   <li>{@link Mode#WIREMOCK_DIRECT} (default): {@code stubFor(...).willReturn(...)} — WireMock client API 직접 호출.
 *       생성된 코드는 {@code com.github.tomakehurst.wiremock.client.WireMock.*} static import 가정.
 *   <li>{@link Mode#TESTLIB}: {@code httpMock.stub(...).respondJson(...).register()} — testlib {@code HttpMockClient} 추상화.
 *       어댑터로 백엔드 (WireMock/MockServer/...) 교체 가능.
 * </ul>
 */
public final class HttpStubComposer {

    public enum Mode {
        WIREMOCK_DIRECT,
        TESTLIB
    }

    private HttpStubComposer() {}

    public static String compose(CapturedHttpCall call) {
        return compose(call, Mode.WIREMOCK_DIRECT);
    }

    public static String compose(CapturedHttpCall call, Mode mode) {
        return switch (mode) {
            case WIREMOCK_DIRECT -> composeWireMockDirect(call);
            case TESTLIB -> composeTestlib(call);
        };
    }

    public static String composeAll(List<CapturedHttpCall> calls) {
        return composeAll(calls, Mode.WIREMOCK_DIRECT);
    }

    public static String composeAll(List<CapturedHttpCall> calls, Mode mode) {
        StringBuilder sb = new StringBuilder();
        for (CapturedHttpCall c : calls) {
            sb.append(compose(c, mode)).append("\n");
        }
        return sb.toString();
    }

    private static String composeWireMockDirect(CapturedHttpCall call) {
        String method = call.method() == null ? "GET" : call.method().toLowerCase(Locale.ROOT);
        UrlParts parts = parseUrl(call.urlConcrete());

        StringBuilder sb = new StringBuilder();
        sb.append("stubFor(").append(method).append("(urlPathEqualTo(\"").append(parts.path).append("\"))");
        for (Map.Entry<String, String> entry : parts.queryParams.entrySet()) {
            sb.append("\n    .withQueryParam(\"").append(entry.getKey()).append("\", equalTo(\"")
                    .append(escape(entry.getValue())).append("\"))");
        }
        sb.append("\n    .willReturn(aResponse()")
                .append(".withStatus(").append(call.responseStatus()).append(")");

        String responseBody = stringify(call.responseBodyObserved());
        if (responseBody != null && !responseBody.isEmpty()) {
            sb.append("\n        .withBody(\"").append(escape(responseBody)).append("\")");
        }
        sb.append("));");
        return sb.toString();
    }

    private static String composeTestlib(CapturedHttpCall call) {
        String method = call.method() == null ? "GET" : call.method().toUpperCase(Locale.ROOT);
        UrlParts parts = parseUrl(call.urlConcrete());

        StringBuilder sb = new StringBuilder();
        sb.append("httpMock.stub(\"").append(parts.path).append("\")");
        sb.append("\n    .method(\"").append(method).append("\")");
        for (Map.Entry<String, String> entry : parts.queryParams.entrySet()) {
            sb.append("\n    .withQueryParam(\"").append(entry.getKey())
                    .append("\", org.hamcrest.Matchers.equalTo(\"")
                    .append(escape(entry.getValue())).append("\"))");
        }
        sb.append("\n    .respondStatus(").append(call.responseStatus()).append(")");

        String responseBody = stringify(call.responseBodyObserved());
        if (responseBody != null && !responseBody.isEmpty()) {
            sb.append("\n    .respondJson(\"").append(escape(responseBody)).append("\")");
        }
        sb.append("\n    .register();");
        return sb.toString();
    }

    private record UrlParts(String path, Map<String, String> queryParams) {}

    private static UrlParts parseUrl(String url) {
        String safe = url == null ? "/" : url;
        String path = safe;
        String queryPart = "";
        int qIdx = safe.indexOf('?');
        if (qIdx >= 0) {
            path = safe.substring(0, qIdx);
            queryPart = safe.substring(qIdx + 1);
        }
        return new UrlParts(path, parseQuery(queryPart));
    }

    private static Map<String, String> parseQuery(String queryPart) {
        if (queryPart == null || queryPart.isEmpty()) return Map.of();
        Map<String, String> result = new TreeMap<>();
        for (String pair : queryPart.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return new LinkedHashMap<>(result);
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String stringify(Object body) {
        if (body == null) return null;
        if (body instanceof String s) return s;
        return body.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
