package io.graphrag.socketmock.server;

import io.graphrag.socketmock.domain.Expectation;
import io.graphrag.socketmock.registry.ExpectationRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockChannelHandlerTest {

    @Test
    void writesRespondBytesWhenMatchFound() {
        ExpectationRegistry reg = new ExpectationRegistry();
        reg.register(Expectation.builder()
                .port(9000).sessionId("s")
                .onReceiveHex("01 02")
                .respondHex("FF EE")
                .build());

        EmbeddedChannel channel = new EmbeddedChannel(new MockChannelHandler(reg, 9000));

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x01, 0x02, 0x03}));

        ByteBuf out = channel.readOutbound();
        assertThat(out).isNotNull();
        byte[] outBytes = new byte[out.readableBytes()];
        out.readBytes(outBytes);
        out.release();
        assertThat(outBytes).containsExactly((byte) 0xFF, (byte) 0xEE);
    }

    @Test
    void noResponseWhenNoMatch() {
        ExpectationRegistry reg = new ExpectationRegistry();
        reg.register(Expectation.builder()
                .port(9000).sessionId("s")
                .onReceiveHex("AA")
                .respondHex("BB")
                .build());

        EmbeddedChannel channel = new EmbeddedChannel(new MockChannelHandler(reg, 9000));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x01, 0x02}));

        assertThat((Object) channel.readOutbound()).isNull();
    }
}
