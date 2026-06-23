package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.graphrag.builder.env.HttpCaptureServer;
import io.graphrag.builder.index.BodyShape;

import java.util.HashSet;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * 외부 호출 응답 형상에서 minimal valid JSON stub을 합성해 HttpCaptureServer에 런타임 등록한다
 * (REQ-006, REQ-008). 동일 (method, pathLiteral)은 한 번만 등록한다(멱등).
 */
public class ExternalStubSynthesizer {

    private final HttpCaptureServer server;
    private final ShapeJsonSynthesizer shapes;
    private final Set<String> registered = new HashSet<>();

    public ExternalStubSynthesizer(HttpCaptureServer server, ShapeJsonSynthesizer shapes) {
        this.server = server;
        this.shapes = shapes;
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

    /** (method, pathLiteral)이 이미 합성 stub으로 등록됐는지. provenance 태깅·stub-ineffective 판정용. */
    public boolean isRegistered(String method, String pathLiteral) {
        return registered.contains(key(method, pathLiteral));
    }

    private static String key(String method, String pathLiteral) {
        return method.toUpperCase() + " " + pathLiteral;
    }
}
