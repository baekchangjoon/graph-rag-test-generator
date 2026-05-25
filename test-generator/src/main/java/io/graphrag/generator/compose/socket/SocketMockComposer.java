package io.graphrag.generator.compose.socket;

import io.graphrag.model.CapturedSocketIO;
import io.graphrag.model.SocketDirection;

import java.util.List;

/**
 * {@link CapturedSocketIO} 시퀀스 → socket-mock-server admin API 등록 코드.
 *
 * <p>출력 예 (testlib helper 컨벤션):
 * <pre>
 * socketMock.bind("inv.host", 9000)
 *     .onReceiveHex("01 02 03")
 *     .respondHex("FF EE")
 *     .register();
 * </pre>
 *
 * <p>Phase 4 minimal: 1쌍(요청/응답) 기준. 다단계 stateful은 후속 phase.
 */
public final class SocketMockComposer {

    private SocketMockComposer() {}

    public static String compose(String host, int port, List<CapturedSocketIO> events) {
        // OUTBOUND(SUT → mock) = onReceive, INBOUND(mock → SUT) = respond
        String request = events.stream()
                .filter(e -> e.direction() == SocketDirection.OUTBOUND)
                .map(CapturedSocketIO::byteHex)
                .findFirst().orElse("");
        String response = events.stream()
                .filter(e -> e.direction() == SocketDirection.INBOUND)
                .map(CapturedSocketIO::byteHex)
                .findFirst().orElse("");
        String sessionField = events.stream()
                .map(CapturedSocketIO::sessionField)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("socketMock.bind(\"").append(host).append("\", ").append(port).append(")");
        if (sessionField != null) {
            sb.append("\n    .withSessionField(\"").append(sessionField).append("\", testId)");
        }
        sb.append("\n    .onReceiveHex(\"").append(request).append("\")");
        sb.append("\n    .respondHex(\"").append(response).append("\")");
        sb.append("\n    .register();");
        return sb.toString();
    }
}
