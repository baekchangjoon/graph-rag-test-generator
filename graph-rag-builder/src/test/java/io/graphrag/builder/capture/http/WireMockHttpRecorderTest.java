package io.graphrag.builder.capture.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.HttpClientType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

class WireMockHttpRecorderTest {

    private WireMockServer wm;
    private RestClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        WireMock.configureFor("localhost", wm.port());
        client = RestClient.builder().baseUrl(wm.baseUrl()).build();
    }

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
        CaptureContext.clear();
    }

    @Test
    void capturesGetCallWithUrlAndStatus() {
        wm.stubFor(get(urlMatching("/inventory/stock.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"available\": 50}")));

        client.get().uri(uriBuilder -> uriBuilder.path("/inventory/stock")
                        .queryParam("type", "EXPRESS").build())
                .retrieve().body(String.class);

        WireMockHttpRecorder recorder = new WireMockHttpRecorder(wm);
        List<CapturedHttpCall> captured = recorder.captureAll("path-1", "inventory");

        assertThat(captured).hasSize(1);
        CapturedHttpCall call = captured.get(0);
        assertThat(call.pathId()).isEqualTo("path-1");
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.urlConcrete()).contains("/inventory/stock");
        assertThat(call.urlConcrete()).contains("type=EXPRESS");
        assertThat(call.responseStatus()).isEqualTo(200);
        assertThat(call.targetExternalId()).isEqualTo("inventory");
        assertThat(call.clientType()).isEqualTo(HttpClientType.OTHER);
    }

    @Test
    void capturesMultipleCallsInOrder() {
        wm.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        client.get().uri("/a").retrieve().body(String.class);
        client.get().uri("/b").retrieve().body(String.class);
        client.get().uri("/c").retrieve().body(String.class);

        WireMockHttpRecorder recorder = new WireMockHttpRecorder(wm);
        List<CapturedHttpCall> captured = recorder.captureAll("p", "ext");

        assertThat(captured).hasSize(3);
        assertThat(captured).extracting(CapturedHttpCall::urlConcrete)
                .anyMatch(s -> s.endsWith("/a"))
                .anyMatch(s -> s.endsWith("/b"))
                .anyMatch(s -> s.endsWith("/c"));
    }

    @Test
    void pipesIntoActiveCaptureContextWhenSet() {
        wm.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        CaptureContext ctx = new CaptureContext("path-ctx");
        CaptureContext.set(ctx);

        client.get().uri("/somewhere").retrieve().body(String.class);

        WireMockHttpRecorder recorder = new WireMockHttpRecorder(wm);
        recorder.captureIntoContext("ext");

        assertThat(ctx.capturedHttpCalls()).hasSize(1);
        assertThat(ctx.capturedHttpCalls().get(0).urlConcrete()).contains("/somewhere");
    }

    @Test
    void capturesEmptyWhenNoCallsMade() {
        WireMockHttpRecorder recorder = new WireMockHttpRecorder(wm);
        assertThat(recorder.captureAll("p", "ext")).isEmpty();
    }
}
