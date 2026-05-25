package io.graphrag.model;

import java.util.List;
import java.util.Objects;

/**
 * 분석 중 캡처된 WebSocket/STOMP 메시지.
 *
 * @param destination STOMP destination ("/topic/orders") 또는 raw endpoint path
 * @param payload 메시지 본문 (text or hex)
 * @param sessionId 세션 식별자 (격리용)
 * @param payloadBindings payload에 들어간 값들의 origin tracking
 */
public record CapturedWsMessage(
        String id,
        String pathId,
        WsMessageDirection direction,
        WsEndpointStyle style,
        String destination,
        Object payload,
        String sessionId,
        List<Binding> payloadBindings) {

    public CapturedWsMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(destination, "destination");
        payloadBindings = List.copyOf(Objects.requireNonNullElse(payloadBindings, List.of()));
    }
}
