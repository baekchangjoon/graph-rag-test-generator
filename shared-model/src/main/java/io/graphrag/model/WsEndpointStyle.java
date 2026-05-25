package io.graphrag.model;

/**
 * WebSocket endpoint 스타일.
 *
 * <ul>
 *   <li>STOMP: Spring `@MessageMapping` + `@SendTo`, SUB/MSG/SEND 프레임
 *   <li>RAW: {@code TextWebSocketHandler} / {@code BinaryWebSocketHandler}
 *   <li>JSR356: {@code @ServerEndpoint}, {@code @OnMessage}
 * </ul>
 */
public enum WsEndpointStyle {
    STOMP,
    RAW,
    JSR356
}
