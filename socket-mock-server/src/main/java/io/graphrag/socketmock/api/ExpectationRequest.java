package io.graphrag.socketmock.api;

/**
 * 외부에서 admin REST로 받는 expectation 등록 요청.
 */
public record ExpectationRequest(
        int port,
        String sessionId,
        String onReceiveHex,
        String respondHex,
        Integer stepOrder) {}
