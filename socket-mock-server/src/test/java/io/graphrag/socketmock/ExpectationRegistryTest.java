package io.graphrag.socketmock;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectationRegistryTest {

    private final ExpectationRegistry registry = new ExpectationRegistry();

    @Test
    void exactMatch_returnsResponse() {
        registry.register(new Expectation("e1", 9000, "01 02 03", "02 00", MatchMode.EXACT));

        Optional<byte[]> response = registry.match(9000, HexCodec.parse("01 02 03"));

        assertThat(response).isPresent();
        assertThat(response.get()).containsExactly(0x02, 0x00);
    }

    @Test
    void exactMatch_rejectsDifferentBytes() {
        registry.register(new Expectation("e1", 9000, "01 02 03", "02 00", MatchMode.EXACT));
        assertThat(registry.match(9000, HexCodec.parse("01 02"))).isEmpty();
    }

    @Test
    void prefixMatch_acceptsLongerPayload() {
        registry.register(new Expectation("e1", 9000, "01 02", "02 00", MatchMode.PREFIX));
        assertThat(registry.match(9000, HexCodec.parse("01 02 99 99"))).isPresent();
    }

    @Test
    void match_isScopedToPort() {
        registry.register(new Expectation("e1", 9000, "01", "02", MatchMode.EXACT));
        assertThat(registry.match(9001, HexCodec.parse("01"))).isEmpty();
    }

    @Test
    void clear_removesAllExpectations() {
        registry.register(new Expectation("e1", 9000, "01", "02", MatchMode.EXACT));
        registry.clear();
        assertThat(registry.match(9000, HexCodec.parse("01"))).isEmpty();
        assertThat(registry.ports()).isEmpty();
    }
}
