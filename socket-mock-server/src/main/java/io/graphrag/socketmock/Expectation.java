package io.graphrag.socketmock;

public record Expectation(
        String id,
        int listenPort,
        String onReceiveHex,
        String respondWithHex,
        MatchMode matchMode) {
}
