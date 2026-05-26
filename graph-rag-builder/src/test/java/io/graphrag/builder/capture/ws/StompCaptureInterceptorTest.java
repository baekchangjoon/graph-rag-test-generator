package io.graphrag.builder.capture.ws;

import io.graphrag.builder.capture.CaptureContext;
import io.graphrag.model.WsEndpointStyle;
import io.graphrag.model.WsMessageDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StompCaptureInterceptorTest {

    @AfterEach
    void cleanup() {
        CaptureContext.clear();
    }

    private Message<byte[]> stompFrame(StompCommand command, String destination, String body) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(command);
        acc.setDestination(destination);
        acc.setSessionId("sess-1");
        byte[] payload = body == null ? new byte[0] : body.getBytes();
        return MessageBuilder.createMessage(payload, acc.getMessageHeaders());
    }

    @Test
    void sendFrameIsCapturedAsOutboundOrConfigured() {
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);

        StompCaptureInterceptor interceptor = new StompCaptureInterceptor(WsMessageDirection.OUTBOUND);

        interceptor.preSend(stompFrame(StompCommand.SEND, "/app/orders/notify", "{\"x\":1}"), null);

        assertThat(ctx.capturedWsMessages()).hasSize(1);
        assertThat(ctx.capturedWsMessages().get(0).direction()).isEqualTo(WsMessageDirection.OUTBOUND);
        assertThat(ctx.capturedWsMessages().get(0).destination()).isEqualTo("/app/orders/notify");
        assertThat(ctx.capturedWsMessages().get(0).style()).isEqualTo(WsEndpointStyle.STOMP);
    }

    @Test
    void messageFrameIsCaptured() {
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);
        StompCaptureInterceptor interceptor = new StompCaptureInterceptor(WsMessageDirection.INBOUND);

        interceptor.preSend(stompFrame(StompCommand.MESSAGE, "/topic/orders", "{\"ok\":true}"), null);

        assertThat(ctx.capturedWsMessages()).hasSize(1);
        assertThat(ctx.capturedWsMessages().get(0).direction()).isEqualTo(WsMessageDirection.INBOUND);
    }

    @Test
    void controlFramesAreIgnored() {
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);
        StompCaptureInterceptor interceptor = new StompCaptureInterceptor(WsMessageDirection.OUTBOUND);

        interceptor.preSend(stompFrame(StompCommand.CONNECT, null, null), null);
        interceptor.preSend(stompFrame(StompCommand.DISCONNECT, null, null), null);

        assertThat(ctx.capturedWsMessages()).isEmpty();
    }

    @Test
    void subscribeIsCaptured() {
        CaptureContext ctx = new CaptureContext("path-1");
        CaptureContext.set(ctx);
        StompCaptureInterceptor interceptor = new StompCaptureInterceptor(WsMessageDirection.OUTBOUND);

        interceptor.preSend(stompFrame(StompCommand.SUBSCRIBE, "/topic/orders", null), null);

        assertThat(ctx.capturedWsMessages()).hasSize(1);
        assertThat(ctx.capturedWsMessages().get(0).destination()).isEqualTo("/topic/orders");
    }

    @Test
    void noCaptureWhenContextNotActive() {
        StompCaptureInterceptor interceptor = new StompCaptureInterceptor(WsMessageDirection.OUTBOUND);
        // CaptureContext.set 호출 없음
        interceptor.preSend(stompFrame(StompCommand.SEND, "/app/x", "body"), null);
        assertThat(CaptureContext.current()).isNull();
    }
}
