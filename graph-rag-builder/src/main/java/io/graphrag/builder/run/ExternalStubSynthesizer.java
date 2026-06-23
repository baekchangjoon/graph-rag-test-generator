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
        String key = method.toUpperCase() + " " + pathLiteral;
        if (!registered.add(key)) {
            return false;
        }
        JsonNode body = shapes.synthesizeBody(shape);
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
}
