package io.graphrag.socketmock.server;

import io.graphrag.socketmock.domain.Expectation;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 들어오는 byte를 ExpectationRegistry에서 매칭하고 응답 byte를 돌려보내는 Netty inbound handler.
 *
 * <p>Phase 0 minimal: prefix 매칭 + 응답 byte 전송. Stateful session/multi-step은 Phase 4에서 강화.
 */
public class MockChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockChannelHandler.class);

    private final ExpectationRegistry registry;
    private final int port;

    public MockChannelHandler(ExpectationRegistry registry, int port) {
        this.registry = registry;
        this.port = port;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        byte[] received = new byte[buf.readableBytes()];
        buf.readBytes(received);
        buf.release();

        Optional<Expectation> match = registry.findMatch(port, received);
        if (match.isPresent()) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(match.get().respondBytes()));
        } else {
            log.debug("no expectation matched on port {} for {} bytes", port, received.length);
            // Phase 0: no-match means no response; client will time out or close
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("MockChannelHandler error on port {}", port, cause);
        ctx.close();
    }
}
