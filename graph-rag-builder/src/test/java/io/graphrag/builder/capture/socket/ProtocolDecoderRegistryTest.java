package io.graphrag.builder.capture.socket;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolDecoderRegistryTest {

    @Test
    void emptyRegistryReturnsEmpty() {
        ProtocolDecoderRegistry r = new ProtocolDecoderRegistry();
        assertThat(r.decode("any:1", new byte[] {0x01})).isEmpty();
        assertThat(r.size()).isZero();
    }

    @Test
    void registeredDecoderApplied() {
        ProtocolDecoderRegistry r = new ProtocolDecoderRegistry();
        r.register(new ProtocolDecoder() {
            @Override public boolean matches(String hostPort) {
                return hostPort.equals("inv:9000");
            }
            @Override public Optional<Object> decode(byte[] bytes) {
                return Optional.of("decoded(" + bytes.length + ")");
            }
        });

        assertThat(r.decode("inv:9000", new byte[] {1, 2, 3}))
                .contains("decoded(3)");
        assertThat(r.decode("other:1", new byte[] {1})).isEmpty();
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void clearRemovesAll() {
        ProtocolDecoderRegistry r = new ProtocolDecoderRegistry();
        r.register(new NoopDecoder());
        r.clear();
        assertThat(r.size()).isZero();
    }

    static class NoopDecoder implements ProtocolDecoder {
        @Override public boolean matches(String hostPort) { return true; }
        @Override public Optional<Object> decode(byte[] bytes) { return Optional.empty(); }
    }
}
