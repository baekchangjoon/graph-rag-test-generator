package io.graphrag.generator.compose.http;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.HttpClientType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStubComposerTestlibTest {

    private CapturedHttpCall sampleGet() {
        return new CapturedHttpCall("h", "p", "GET",
                "/inventory/stock?type=EXPRESS", "/inventory/stock?type=EXPRESS",
                List.of(), Map.of(), null, List.of(),
                200, "{\"available\":50}", List.of(),
                HttpClientType.OTHER, "ext");
    }

    @Test
    void testlibModeUsesHttpMockClientApi() {
        String stub = HttpStubComposer.compose(sampleGet(), HttpStubComposer.Mode.TESTLIB);

        assertThat(stub)
                .contains("httpMock.stub(\"/inventory/stock\")")
                .contains(".method(\"GET\")")
                .contains(".withQueryParam(\"type\"")
                .contains(".respondStatus(200)")
                .contains(".respondJson(")
                .contains(".register();");
    }

    @Test
    void wireMockDirectModeUsesStubFor() {
        String stub = HttpStubComposer.compose(sampleGet(), HttpStubComposer.Mode.WIREMOCK_DIRECT);

        assertThat(stub)
                .contains("stubFor(")
                .contains("get(urlPathEqualTo")
                .contains("willReturn(aResponse()")
                .doesNotContain("httpMock.stub");
    }

    @Test
    void defaultModeIsWireMockDirect() {
        String defaultStub = HttpStubComposer.compose(sampleGet());
        String explicit = HttpStubComposer.compose(sampleGet(), HttpStubComposer.Mode.WIREMOCK_DIRECT);

        assertThat(defaultStub).isEqualTo(explicit);
    }

    @Test
    void testlibModeRespectsHttpMethod() {
        CapturedHttpCall post = new CapturedHttpCall("h", "p", "POST",
                "/order", "/order", List.of(), Map.of(), null, List.of(),
                201, "{}", List.of(), HttpClientType.OTHER, "ext");
        String stub = HttpStubComposer.compose(post, HttpStubComposer.Mode.TESTLIB);
        assertThat(stub).contains(".method(\"POST\")")
                .contains(".respondStatus(201)");
    }
}
