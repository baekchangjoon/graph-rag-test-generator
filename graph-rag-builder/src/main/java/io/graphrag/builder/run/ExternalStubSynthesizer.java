package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.env.NoTraceKey;
import io.graphrag.builder.env.TraceKey;
import io.graphrag.builder.index.BodyShape;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * 외부 호출 응답 형상에서 minimal valid JSON stub을 합성해 HttpCaptureServer에 런타임 등록한다
 * (REQ-006, REQ-008). 단계1 전역 stub은 (method, pathLiteral)당 한 번만 등록한다(멱등).
 *
 * <p>단계2 변형(REQ-008): 같은 (method, pathLiteral)에 trace-id로 격리된 변형 stub을 공존 등록한다.
 * 전역 stub의 멱등 키(registered Set)와 분리된 별도 {@code variants} Map(UUID→(method,path))으로
 * 추적해, removeVariant에서 UUID로 정확히 제거하고 전역 stub으로 복원한다.
 */
public class ExternalStubSynthesizer {

    /** 변형 stub은 전역(단계1) stub보다 높은 우선순위(낮은 숫자)로 등록 → 격리 요청만 변형이 매칭. */
    private static final int VARIANT_PRIORITY = 1;

    private final HttpCaptureServer server;
    private final ShapeJsonSynthesizer shapes;
    private final TraceKey traceKey;
    private final Set<String> registered = new HashSet<>();
    private final Map<UUID, String> variants = new LinkedHashMap<>();   // UUID → "METHOD /path"

    public ExternalStubSynthesizer(HttpCaptureServer server, ShapeJsonSynthesizer shapes) {
        this(server, shapes, new NoTraceKey());
    }

    public ExternalStubSynthesizer(HttpCaptureServer server, ShapeJsonSynthesizer shapes, TraceKey traceKey) {
        this.server = server;
        this.shapes = shapes;
        this.traceKey = traceKey != null ? traceKey : new NoTraceKey();
    }

    /**
     * (method, pathLiteral)에 대해 형상 합성 stub을 등록한다.
     * 이미 등록된 키면 false(재등록 안 함), 새로 등록하면 true.
     */
    public boolean register(String method, String pathLiteral, BodyShape shape) {
        String key = key(method, pathLiteral);
        if (registered.contains(key)) {
            return false;
        }
        // 형상 합성을 먼저 — 해소 불가 형상(UnsupportedShapeException)이면 키를 등록하지 않고 던져서
        // 호출부가 unsynthesizable-shape loud-fail로 surface 하게 한다(silent 등록 금지, REQ-010).
        JsonNode body = shapes.synthesizeBody(shape);
        registered.add(key);
        MappingBuilder builder = method.equalsIgnoreCase("POST")
                ? post(urlPathEqualTo(pathLiteral))
                : get(urlPathEqualTo(pathLiteral));
        StubMapping mapping = builder.willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body.toString())).build();
        server.registerStub(mapping);
        return true;
    }

    /**
     * 변형 stub을 등록한다(단계2, REQ-008). 전역 stub의 멱등 차단을 받지 않고, 같은 (method, path)에
     * 여러 변형이 trace-id로 격리되어 공존한다. traceKey.matchFor(traceId)가 non-null이면 그 헤더
     * (traceKey.headerName())로 매칭(otel: traceparent containing, sleuth: X-B3-TraceId equalTo).
     * none 모드(matchFor null)면 헤더 조건 없이 전역-우선 priority로만 등록(순차 교체).
     *
     * @return 등록된 StubMapping의 UUID (removeVariant 키)
     */
    public UUID registerVariant(String method, String pathLiteral, JsonNode body, String traceId) {
        MappingBuilder builder = method.equalsIgnoreCase("POST")
                ? post(urlPathEqualTo(pathLiteral))
                : get(urlPathEqualTo(pathLiteral));
        StringValuePattern match = traceKey.matchFor(traceId);
        if (match != null) {
            builder = builder.withHeader(traceKey.headerName(), match);
        }
        StubMapping mapping = builder.atPriority(VARIANT_PRIORITY)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body.toString())).build();
        server.registerStub(mapping);
        UUID id = mapping.getId();
        variants.put(id, key(method, pathLiteral));
        return id;
    }

    /** 변형 stub을 UUID로 제거한다(none 순차 교체·정리). 제거 후 그 요청은 전역 stub으로 복원. */
    public void removeVariant(UUID id) {
        if (variants.remove(id) != null) {
            server.removeStub(id);
        }
    }

    /** (method, pathLiteral)에 현재 활성 변형 stub이 있는지. provenance 태깅용(REQ-004, Task 7). */
    public boolean isVariantRegistered(String method, String pathLiteral) {
        return variants.containsValue(key(method, pathLiteral));
    }

    /** (method, pathLiteral)이 이미 합성 stub으로 등록됐는지. provenance 태깅·stub-ineffective 판정용. */
    public boolean isRegistered(String method, String pathLiteral) {
        return registered.contains(key(method, pathLiteral));
    }

    private static String key(String method, String pathLiteral) {
        return method.toUpperCase() + " " + pathLiteral;
    }
}
