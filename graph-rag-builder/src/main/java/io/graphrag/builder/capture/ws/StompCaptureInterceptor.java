package io.graphrag.builder.capture.ws;

import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.model.Binding;
import io.graphrag.model.CapturedWsMessage;
import io.graphrag.model.WsEndpointStyle;
import io.graphrag.model.WsMessageDirection;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring STOMP 채널 인터셉터: 활성 {@link CaptureContext}에 메시지를
 * {@link CapturedWsMessage}로 기록.
 *
 * <p>설치 위치:
 * <ul>
 *   <li>inbound channel — SUBSCRIBE/SEND/MESSAGE → {@link WsMessageDirection#INBOUND} 또는 OUTBOUND
 *   <li>outbound channel — MESSAGE/ERROR → {@link WsMessageDirection#INBOUND} (서버→클라이언트)
 * </ul>
 *
 * <p>Phase 3+: payload binding tracking은 후속 작업.
 */
public final class StompCaptureInterceptor implements ChannelInterceptor {

    private final WsMessageDirection direction;

    public StompCaptureInterceptor(WsMessageDirection direction) {
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        CaptureContext ctx = CaptureContext.current();
        if (ctx == null) return message;

        StompHeaderAccessor acc = StompHeaderAccessor.wrap(message);
        StompCommand command = acc.getCommand();
        if (command == null) return message;

        // CONNECT/DISCONNECT/HEARTBEAT 등 페이로드 없는 프레임은 기록 안 함
        if (command != StompCommand.SEND
                && command != StompCommand.MESSAGE
                && command != StompCommand.SUBSCRIBE) {
            return message;
        }

        String destination = acc.getDestination() == null ? "" : acc.getDestination();
        String sessionId = acc.getSessionId() == null
                ? UUID.randomUUID().toString()
                : acc.getSessionId();
        Object payload = message.getPayload();

        ctx.addCapturedWsMessage(new CapturedWsMessage(
                "ws-" + UUID.randomUUID(),
                ctx.pathId(),
                direction,
                WsEndpointStyle.STOMP,
                destination,
                payload,
                sessionId,
                List.<Binding>of()));

        return message;
    }
}
