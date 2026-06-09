package io.graphrag.socketmock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HexCodecTest {

    @Test
    void parse_acceptsSpaceSeparatedHex() {
        assertThat(HexCodec.parse("01 02 ff")).containsExactly(0x01, 0x02, (byte) 0xff);
    }

    @Test
    void parse_acceptsCompactHex() {
        assertThat(HexCodec.parse("0102FF")).containsExactly(0x01, 0x02, (byte) 0xff);
    }

    @Test
    void parse_rejectsOddLength() {
        assertThatThrownBy(() -> HexCodec.parse("012")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void format_producesSpaceSeparatedLowercase() {
        assertThat(HexCodec.format(new byte[]{0x01, (byte) 0xff})).isEqualTo("01 ff");
    }
}
