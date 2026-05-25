package io.graphrag.socketmock.server;

import io.graphrag.socketmock.registry.ExpectationRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 등록된 포트에 대해 idempotent하게 Netty TCP 서버를 띄움.
 *
 * <p>{@link #ensureBound(int)} 호출 시 해당 포트가 이미 바인딩 중이면 noop, 아니면 새로 바인딩.
 */
@Component
public class NettyServerManager {

    private static final Logger log = LoggerFactory.getLogger(NettyServerManager.class);

    private final ExpectationRegistry registry;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final Map<Integer, Channel> boundChannels = new HashMap<>();

    public NettyServerManager(ExpectationRegistry registry) {
        this.registry = registry;
    }

    public synchronized int ensureBound(int port) {
        if (boundChannels.containsKey(port)) {
            return port;
        }
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 16)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // MockChannelHandler가 ByteBuf를 직접 처리. decoder 불필요.
                            ch.pipeline().addLast(new MockChannelHandler(registry, port));
                        }
                    });
            Channel ch = b.bind(new InetSocketAddress(port)).sync().channel();
            boundChannels.put(port, ch);
            int actualPort = ((InetSocketAddress) ch.localAddress()).getPort();
            log.info("Bound mock TCP server on port {} (requested {})", actualPort, port);
            return actualPort;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while binding port " + port, ex);
        }
    }

    public synchronized boolean isBound(int port) {
        Channel ch = boundChannels.get(port);
        return ch != null && ch.isActive();
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (Channel ch : boundChannels.values()) {
            ch.close();
        }
        boundChannels.clear();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
