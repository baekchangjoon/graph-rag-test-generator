package io.graphrag.generator.compose.http;

import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.HttpClientType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStubComposerTest {

    private CapturedHttpCall getCall(String url, int status, String body) {
        return new CapturedHttpCall(
                "h-1", "path-1", "GET", url, url, List.of(),
                Map.of(), null, List.of(),
                status, body, List.of(),
                HttpClientType.OTHER, "ext");
    }

    @Test
    void generatesStubFragmentForGet() {
        CapturedHttpCall call = getCall("/inventory/stock?type=EXPRESS", 200,
                "{\"available\": 50}");

        String stub = HttpStubComposer.compose(call);

        assertThat(stub).contains("stubFor(");
        assertThat(stub).contains("get(urlPathEqualTo");
        assertThat(stub).contains("\"/inventory/stock\"");
        assertThat(stub).contains("withQueryParam(\"type\", equalTo(\"EXPRESS\"))");
        assertThat(stub).contains("willReturn(");
        assertThat(stub).contains("withStatus(200)");
        assertThat(stub).contains("withBody(");
    }

    @Test
    void generatesStubFragmentForPost() {
        CapturedHttpCall call = new CapturedHttpCall(
                "h-2", "path-1", "POST", "/inventory/reserve", "/inventory/reserve",
                List.of(),
                Map.of("Content-Type", "application/json"),
                "{\"qty\":5}",
                List.of(),
                201, "{\"id\":\"r-1\"}", List.of(),
                HttpClientType.OTHER, "ext");

        String stub = HttpStubComposer.compose(call);

        assertThat(stub).contains("post(urlPathEqualTo(\"/inventory/reserve\"))");
        assertThat(stub).contains("withStatus(201)");
    }

    @Test
    void noQueryParamsWhenUrlHasNone() {
        String stub = HttpStubComposer.compose(getCall("/inventory/stock", 200, "{}"));
        assertThat(stub).doesNotContain("withQueryParam");
    }

    @Test
    void multipleStubsComposeToOneStringSeparatedByLines() {
        CapturedHttpCall c1 = getCall("/a", 200, "{}");
        CapturedHttpCall c2 = getCall("/b", 404, "{}");
        String joined = HttpStubComposer.composeAll(List.of(c1, c2));

        assertThat(joined).contains("/a").contains("/b");
        long stubForCount = joined.lines().filter(l -> l.contains("stubFor(")).count();
        assertThat(stubForCount).isEqualTo(2);
    }

    @Test
    void deterministicOutput() {
        CapturedHttpCall c = getCall("/x?a=1&b=2", 200, "{\"k\":\"v\"}");
        assertThat(HttpStubComposer.compose(c))
                .isEqualTo(HttpStubComposer.compose(c));
    }
}
