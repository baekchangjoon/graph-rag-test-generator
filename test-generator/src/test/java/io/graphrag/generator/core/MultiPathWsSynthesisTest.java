package io.graphrag.generator.core;

import io.graphrag.generator.verify.CompileResult;
import io.graphrag.generator.verify.JavaSourceCompiler;
import io.graphrag.model.Binding;
import io.graphrag.model.CapturedWsMessage;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.WsEndpointStyle;
import io.graphrag.model.WsMessageDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 마무리: TestSynthesizer가 CapturedWsMessage를 STOMP client 코드로 합성.
 */
class MultiPathWsSynthesisTest {

    private final Endpoint endpoint = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo-sut", "OrdersController", "create", false, List.of());

    private ExploredPath path(String id, int status) {
        return new ExploredPath(id, endpoint.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), status, null, "cov-" + id, "v1");
    }

    private CapturedWsMessage inboundMessage(String dest, Object payload) {
        return new CapturedWsMessage("w-in", "p1",
                WsMessageDirection.INBOUND, WsEndpointStyle.STOMP,
                dest, payload, "sess", List.<Binding>of());
    }

    private CapturedWsMessage outboundMessage(String dest, Object payload) {
        return new CapturedWsMessage("w-out", "p1",
                WsMessageDirection.OUTBOUND, WsEndpointStyle.STOMP,
                dest, payload, "sess", List.<Binding>of());
    }

    @Test
    void inboundMessageProducesSubscribeAndPoll() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(), List.of(),
                List.of(inboundMessage("/topic/orders", "{\"status\":\"OK\"}")));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        assertThat(code)
                .contains("WS_BASE_URI")
                .contains("connectStomp")
                .contains("wsSession.subscribe(\"/topic/orders\"")
                .contains("wsReceived.poll(")
                .contains("wsSession.disconnect()");
    }

    @Test
    void outboundMessageProducesSendCall() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(), List.of(),
                List.of(outboundMessage("/app/orders/notify",
                        Map.of("orderId", "o-1", "message", "hi"))));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        assertThat(code)
                .contains("wsSession.send(\"/app/orders/notify\"")
                .contains("getBytes(java.nio.charset.StandardCharsets.UTF_8)");
    }

    @Test
    void pathWithoutWsDoesNotImportStomp() {
        PathContext pc = new PathContext(path("p1", 400), List.of(), List.of(), List.of(), List.of());
        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        assertThat(code)
                .doesNotContain("WS_BASE_URI")
                .doesNotContain("connectStomp")
                .doesNotContain("wsSession.");
    }

    @Test
    void synthesizedWsClassCompiles() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(), List.of(),
                List.of(inboundMessage("/topic/orders", "{\"x\":1}"),
                        outboundMessage("/app/orders/notify", Map.of("a", "b"))));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        CompileResult r = JavaSourceCompiler.compile("gen.OrdersPostTest", code);
        assertThat(r.success())
                .as("ws-integrated synth should compile. diagnostics=" + r.diagnostics())
                .isTrue();
    }

    @Test
    void mixedPathsIsolateWsToOwningPath() {
        PathContext withWs = new PathContext(
                path("ws-p", 201), List.of(), List.of(), List.of(),
                List.of(inboundMessage("/topic/x", "{}")));
        PathContext bare = new PathContext(path("bare-p", 400), List.of(), List.of(), List.of(), List.of());

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(withWs, bare), "gen"));

        int withWsIdx = code.indexOf("void path_ws_p(");
        int bareIdx = code.indexOf("void path_bare_p(");
        int firstSub = code.indexOf("wsSession.subscribe(");

        assertThat(firstSub).isGreaterThan(withWsIdx).isLessThan(bareIdx);
        // bare path 메소드 내에는 subscribe 등장 안 함
        int subAfterBare = code.indexOf("wsSession.subscribe(", bareIdx);
        assertThat(subAfterBare).isEqualTo(-1);
    }
}
