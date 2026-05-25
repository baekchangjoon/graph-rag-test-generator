package io.graphrag.socketmock.registry;

import io.graphrag.socketmock.domain.Expectation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectationRegistryTest {

    private final ExpectationRegistry registry = new ExpectationRegistry();

    @Test
    void registersAndRetrievesByPort() {
        Expectation e = Expectation.builder()
                .port(9000)
                .sessionId("sess-1")
                .onReceiveHex("01 02")
                .respondHex("FF")
                .build();

        registry.register(e);

        assertThat(registry.expectationsForPort(9000)).hasSize(1);
        assertThat(registry.expectationsForPort(9000).get(0).respondBytes())
                .containsExactly((byte) 0xFF);
    }

    @Test
    void matchesByPrefix() {
        registry.register(Expectation.builder()
                .port(9000).sessionId("s")
                .onReceiveHex("01 02 03")
                .respondHex("FF")
                .build());

        Optional<Expectation> match = registry.findMatch(9000, hex("01 02 03 04"));
        assertThat(match).isPresent();
    }

    @Test
    void differentPortsDoNotCollide() {
        registry.register(Expectation.builder()
                .port(9000).sessionId("a").onReceiveHex("01").respondHex("0A").build());
        registry.register(Expectation.builder()
                .port(9001).sessionId("b").onReceiveHex("01").respondHex("0B").build());

        assertThat(registry.expectationsForPort(9000)).hasSize(1);
        assertThat(registry.expectationsForPort(9001)).hasSize(1);
        assertThat(registry.findMatch(9001, hex("01")).orElseThrow().respondBytes())
                .containsExactly((byte) 0x0B);
    }

    @Test
    void removeBySessionRemovesAllForThatSession() {
        registry.register(Expectation.builder()
                .port(9000).sessionId("sess-A")
                .onReceiveHex("01").respondHex("A1").build());
        registry.register(Expectation.builder()
                .port(9000).sessionId("sess-A")
                .onReceiveHex("02").respondHex("A2").build());
        registry.register(Expectation.builder()
                .port(9000).sessionId("sess-B")
                .onReceiveHex("03").respondHex("B1").build());

        registry.removeSession("sess-A");

        assertThat(registry.expectationsForPort(9000)).hasSize(1);
        assertThat(registry.expectationsForPort(9000).get(0).sessionId()).isEqualTo("sess-B");
    }

    @Test
    void clearWipesAll() {
        registry.register(Expectation.builder()
                .port(9000).sessionId("x")
                .onReceiveHex("01").respondHex("FF").build());
        registry.clear();
        assertThat(registry.expectationsForPort(9000)).isEmpty();
    }

    @Test
    void noMatchReturnsEmpty() {
        registry.register(Expectation.builder()
                .port(9000).sessionId("a")
                .onReceiveHex("AA")
                .respondHex("BB")
                .build());
        assertThat(registry.findMatch(9000, hex("CC DD"))).isEmpty();
    }

    private static byte[] hex(String h) {
        String clean = h.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
