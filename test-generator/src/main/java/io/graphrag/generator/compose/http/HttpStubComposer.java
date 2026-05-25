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
 * <p>출력 예:
 * <pre>
 * stubFor(get(urlPathEqualTo("/inventory/stock"))
 *     .withQueryParam("type", equalTo("EXPRESS"))
 *     .willReturn(aResponse().withStatus(200).withBody("{...}")));
 * </pre>
 *
 * <p>생성된 코드는 {@code com.github.tomakehurst.wiremock.client.WireMock.*} static import 가정.
 */
public final class HttpStubComposer {

    private HttpStubComposer() {}

    public static String compose(CapturedHttpCall call) {
        String method = call.method() == null ? "GET" : call.method().toLowerCase(Locale.ROOT);
        String url = call.urlConcrete() == null ? "/" : call.urlConcrete();
        String path = url;
        String queryPart = "";
        int qIdx = url.indexOf('?');
        if (qIdx >= 0) {
            path = url.substring(0, qIdx);
            queryPart = url.substring(qIdx + 1);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("stubFor(").append(method).append("(urlPathEqualTo(\"").append(path).append("\"))");

        Map<String, String> queryParams = parseQuery(queryPart);
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
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

    public static String composeAll(List<CapturedHttpCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (CapturedHttpCall c : calls) {
            sb.append(compose(c)).append("\n");
        }
        return sb.toString();
    }

    /** key 정렬 LinkedHashMap (TreeMap)으로 결정적. */
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
