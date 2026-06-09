package io.graphrag.socketmock;

import java.util.HexFormat;

public final class HexCodec {

    private HexCodec() {
    }

    public static byte[] parse(String hex) {
        String compact = hex.replaceAll("\\s+", "").toLowerCase();
        if (compact.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string must have even length: " + hex);
        }
        return HexFormat.of().parseHex(compact);
    }

    public static String format(byte[] bytes) {
        return HexFormat.ofDelimiter(" ").formatHex(bytes);
    }
}
