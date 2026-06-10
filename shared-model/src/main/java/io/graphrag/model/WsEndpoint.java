package io.graphrag.model;

/** STOMP 메시지 endpoint 사실 (roadmap 3.1). */
public record WsEndpoint(
        String id,
        String wsPath,
        String appPrefix,
        String destination,
        String sendTo,
        String handlerClass,
        String handlerMethod,
        String payloadType) {
}
