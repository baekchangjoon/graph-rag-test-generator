package io.graphrag.generator.result;

import io.graphrag.generator.core.MultiPathSynthesisInput;
import io.graphrag.generator.core.PathContext;
import io.graphrag.model.CapturedHttpCall;
import io.graphrag.model.CapturedSocketIO;
import io.graphrag.model.CapturedWsMessage;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpClientType;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SocketDirection;
import io.graphrag.model.SocketFramework;
import io.graphrag.model.SocketProtocol;
import io.graphrag.model.WsEndpointStyle;
import io.graphrag.model.WsMessageDirection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationResultBuilderTest {

    private final Endpoint ep = new Endpoint(
            "POST:/api/orders", HttpMethod.POST, "/api/orders",
            "demo", "C", "m", false, List.of());

    private ExploredPath path(String id, int status) {
        return new ExploredPath(id, ep.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), status, null, "sig", "v1");
    }

    @Test
    void emptyInputProducesSuccessWithMinimalArtifact() {
        PathContext pc = new PathContext(path("p1", 201), List.of());
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                ep, List.of(pc), "gen");

        GenerationResult r = GenerationResultBuilder.build(input, Path.of("/tmp/x.java"));

        assertThat(r.status()).isEqualTo(GenerationResult.Status.SUCCESS);
        assertThat(r.newArtifacts()).hasSize(1);
        assertThat(r.newArtifacts().get(0).coversPaths()).containsExactly("p1");
        assertThat(r.parallelSafety()).isEmpty();
        assertThat(r.rationale()).hasSize(1);
        assertThat(r.rationale().get(0).pathId()).isEqualTo("p1");
    }

    @Test
    void nullWrittenFileProducesFailedStatus() {
        PathContext pc = new PathContext(path("p1", 201), List.of());
        MultiPathSynthesisInput input = new MultiPathSynthesisInput(
                ep, List.of(pc), "gen");

        GenerationResult r = GenerationResultBuilder.build(input, null);

        assertThat(r.status()).isEqualTo(GenerationResult.Status.FAILED);
        assertThat(r.newArtifacts()).isEmpty();
    }

    @Test
    void socketWithoutSessionFieldEmitsSerialRequirement() {
        CapturedSocketIO io = new CapturedSocketIO("s-1", "p1", SocketDirection.OUTBOUND,
                "h", 9000, "01", "msg", SocketProtocol.TCP, SocketFramework.NETTY, null, null);
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(), List.of(io), List.of());

        GenerationResult r = GenerationResultBuilder.build(
                new MultiPathSynthesisInput(ep, List.of(pc), "gen"),
                Path.of("/tmp/x.java"));

        assertThat(r.status()).isEqualTo(GenerationResult.Status.PARTIAL);
        assertThat(r.parallelSafety()).hasSize(1)
                .first().satisfies(e -> {
                    assertThat(e.reason()).isEqualTo("SOCKET_NO_SESSION");
                });
    }

    @Test
    void rationaleSummarizesCaptureSources() {
        CapturedHttpCall http = new CapturedHttpCall("h-1", "p1", "GET", "/x", "/x",
                List.of(), Map.of(), null, List.of(), 200, "{}",
                List.of(), HttpClientType.OTHER, "ext");
        CapturedWsMessage ws = new CapturedWsMessage("w-1", "p1",
                WsMessageDirection.INBOUND, WsEndpointStyle.STOMP,
                "/t", null, "s", List.of());
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(http), List.of(), List.of(ws));

        GenerationResult r = GenerationResultBuilder.build(
                new MultiPathSynthesisInput(ep, List.of(pc), "gen"),
                Path.of("/tmp/x.java"));

        assertThat(r.rationale()).hasSize(1)
                .first().satisfies(e -> {
                    assertThat(e.capturedSources()).contains("HTTP(1)", "WS(1)");
                });
    }

    @Test
    void recommendsEnvVarsByCapturePresence() {
        CapturedWsMessage ws = new CapturedWsMessage("w-1", "p1",
                WsMessageDirection.INBOUND, WsEndpointStyle.STOMP,
                "/t", null, "s", List.of());
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(), List.of(), List.of(ws));

        GenerationResult r = GenerationResultBuilder.build(
                new MultiPathSynthesisInput(ep, List.of(pc), "gen"),
                Path.of("/tmp/x.java"));

        assertThat(r.recommendations()).anyMatch(rec -> rec.kind().equals("REQUIRE_WS_BASE_URI"));
    }
}
