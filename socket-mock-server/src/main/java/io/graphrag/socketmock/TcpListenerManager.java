package io.graphrag.socketmock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** expectation이 등록된 포트마다 Netty TCP 리스너를 1개씩 유지. */
@Component
public class TcpListenerManager {

    private static final Logger log = LoggerFactory.getLogger(TcpListenerManager.class);

    private final ExpectationRegistry registry;
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Map<Integer, Channel> listeners = new ConcurrentHashMap<>();

    public TcpListenerManager(ExpectationRegistry registry) {
        this.registry = registry;
    }

    /** 동시 리스너 상한 (자원 고갈 방지). */
    static final int MAX_LISTENERS = 64;

    public synchronized void ensureListening(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("listenPort must be in 1024..65535: " + port);
        }
        if (!listeners.containsKey(port) && listeners.size() >= MAX_LISTENERS) {
            throw new IllegalStateException("too many listeners (max " + MAX_LISTENERS + ")");
        }
        listeners.computeIfAbsent(port, this::bind);
    }

    private Channel bind(int port) {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new MockHandler(port));
                    }
                });
        Channel channel = bootstrap.bind(port).syncUninterruptibly().channel();
        log.info("socket-mock listening on tcp port {}", port);
        return channel;
    }

    @PreDestroy
    public void shutdown() {
        listeners.values().forEach(Channel::close);
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private class MockHandler extends ChannelInboundHandlerAdapter {

        private final int port;

        MockHandler(int port) {
            this.port = port;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] received = new byte[buf.readableBytes()];
                buf.readBytes(received);
                log.debug("port {} received: {}", port, HexCodec.format(received));
                Optional<byte[]> response = registry.match(port, received);
                if (response.isPresent()) {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(response.get()));
                } else {
                    log.warn("port {} no expectation matched for: {}", port, HexCodec.format(received));
                }
            } finally {
                buf.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("port {} handler error", port, cause);
            ctx.close();
        }
    }
}
