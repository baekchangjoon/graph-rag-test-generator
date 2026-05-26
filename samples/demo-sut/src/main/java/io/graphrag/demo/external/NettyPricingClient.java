package io.graphrag.demo.external;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4 외부 가격 시스템 클라이언트 — Netty 기반 TCP.
 *
 * <p>운영에서는 외부 inventory pricing 데몬을 호출. 분석/테스트에서는 socket-mock-server로 대체.
 *
 * <p>프로토콜: 요청 4 byte (item code), 응답 4 byte (price BE int).
 */
@Component
public class NettyPricingClient {

    private final String host;
    private final int port;

    public NettyPricingClient(
            @Value("${external.pricing.host:127.0.0.1}") String host,
            @Value("${external.pricing.port:9000}") int port) {
        this.host = host;
        this.port = port;
    }

    /** {@code itemCode} (4 bytes)를 보내고 응답 4 byte로부터 price를 읽어 반환. 실패 시 -1. */
    public int fetchPrice(byte[] itemCode) {
        if (itemCode == null || itemCode.length != 4) {
            throw new IllegalArgumentException("itemCode must be 4 bytes");
        }
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            LinkedBlockingDeque<byte[]> received = new LinkedBlockingDeque<>();
            Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ByteBuf buf = (ByteBuf) msg;
                                    byte[] arr = new byte[buf.readableBytes()];
                                    buf.readBytes(arr);
                                    buf.release();
                                    received.add(arr);
                                }
                            });
                        }
                    });
            Channel ch = b.connect(host, port).sync().channel();
            ch.writeAndFlush(Unpooled.wrappedBuffer(itemCode)).sync();
            byte[] resp = received.poll(3, TimeUnit.SECONDS);
            ch.close().sync();
            if (resp == null || resp.length < 4) return -1;
            return ((resp[0] & 0xFF) << 24)
                    | ((resp[1] & 0xFF) << 16)
                    | ((resp[2] & 0xFF) << 8)
                    | (resp[3] & 0xFF);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            group.shutdownGracefully();
        }
    }
}
