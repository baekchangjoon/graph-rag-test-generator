package io.graphrag.generator.core;

import io.graphrag.generator.verify.CompileResult;
import io.graphrag.generator.verify.JavaSourceCompiler;
import io.graphrag.model.CapturedSocketIO;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.HttpMethod;
import io.graphrag.model.PathExplorerKind;
import io.graphrag.model.SampleInput;
import io.graphrag.model.SocketDirection;
import io.graphrag.model.SocketFramework;
import io.graphrag.model.SocketProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 통합: TestSynthesizer가 CapturedSocketIO를 socket-mock admin POST 코드로 합성.
 */
class MultiPathSocketSynthesisTest {

    private final Endpoint endpoint = new Endpoint(
            "POST:/api/sockets/run", HttpMethod.POST, "/api/sockets/run",
            "demo-sut", "SocketController", "run", false, List.of());

    private ExploredPath path(String id, int status) {
        return new ExploredPath(id, endpoint.id(), PathExplorerKind.MANUAL,
                new SampleInput(Map.of(), Map.of(), Map.of(), Map.of()),
                null, List.of(), status, null, "cov-" + id, "v1");
    }

    private CapturedSocketIO io(String pathId, SocketDirection dir, String hex) {
        return new CapturedSocketIO(
                "s-" + pathId + "-" + dir, pathId, dir,
                "inv.host", 9000, hex, "msg",
                SocketProtocol.TCP, SocketFramework.NETTY, null, null);
    }

    @Test
    void socketCaptureInjectsAdminPostCode() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(),
                List.of(io("p1", SocketDirection.OUTBOUND, "01 02 03"),
                        io("p1", SocketDirection.INBOUND, "FF EE")));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        assertThat(code)
                .contains("SOCKET_MOCK_ADMIN")
                .contains("registerSocketExpectation(")
                .contains("9000")
                .contains("01 02 03")
                .contains("FF EE");
    }

    @Test
    void pathWithoutSocketDoesNotEmitHelper() {
        PathContext pc = new PathContext(path("p1", 400), List.of(), List.of(), List.of());

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        assertThat(code).doesNotContain("registerSocketExpectation")
                .doesNotContain("SOCKET_MOCK_ADMIN");
    }

    @Test
    void mixedPathsOnlyInjectInPathsWithSocket() {
        PathContext withSocket = new PathContext(
                path("p1", 201), List.of(), List.of(),
                List.of(io("p1", SocketDirection.OUTBOUND, "AA"),
                        io("p1", SocketDirection.INBOUND, "BB")));
        PathContext bare = new PathContext(path("p2", 400), List.of(), List.of(), List.of());

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(withSocket, bare), "gen"));

        int p1Idx = code.indexOf("void path_p1(");
        int p2Idx = code.indexOf("void path_p2(");
        // 호출 패턴 (포트 9000 포함)으로 매칭. 메소드 선언은 "int port,"라 매칭 안 됨.
        int firstCall = code.indexOf("registerSocketExpectation(9000,");

        assertThat(firstCall).isGreaterThan(p1Idx).isLessThan(p2Idx);
        int callAfterP2 = code.indexOf("registerSocketExpectation(9000,", p2Idx);
        assertThat(callAfterP2).isEqualTo(-1);
    }

    @Test
    void synthesizedSocketClassCompiles() {
        PathContext pc = new PathContext(
                path("p1", 201), List.of(), List.of(),
                List.of(io("p1", SocketDirection.OUTBOUND, "01 02"),
                        io("p1", SocketDirection.INBOUND, "FF")));

        String code = TestSynthesizer.synthesizeMulti(
                new MultiPathSynthesisInput(endpoint, List.of(pc), "gen"));

        CompileResult r = JavaSourceCompiler.compile("gen.RunPostTest", code);
        assertThat(r.success())
                .as("socket-integrated synth should compile. diagnostics=" + r.diagnostics())
                .isTrue();
    }
}
