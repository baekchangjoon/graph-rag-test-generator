package io.graphrag.socketmock.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Socket mock expectation: 특정 포트로 들어온 byte 패턴에 대한 응답 정의.
 *
 * @param id 발급된 expectation id
 * @param port 리스닝 포트
 * @param sessionId 격리용 세션 식별자 (testId 기반)
 * @param onReceiveBytes 수신 byte 패턴 (prefix 매칭)
 * @param respondBytes 응답 byte
 * @param stepOrder 다단계 프로토콜의 step 순서 (1부터, 단일 step 응답은 0)
 */
public record Expectation(
        String id,
        int port,
        String sessionId,
        byte[] onReceiveBytes,
        byte[] respondBytes,
        int stepOrder) {

    public Expectation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(onReceiveBytes, "onReceiveBytes");
        Objects.requireNonNull(respondBytes, "respondBytes");
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private int port;
        private String sessionId;
        private byte[] onReceiveBytes;
        private byte[] respondBytes;
        private int stepOrder = 0;

        public Builder id(String v) { this.id = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder onReceive(byte[] v) { this.onReceiveBytes = v; return this; }
        public Builder onReceiveHex(String hex) { this.onReceiveBytes = parseHex(hex); return this; }
        public Builder respond(byte[] v) { this.respondBytes = v; return this; }
        public Builder respondHex(String hex) { this.respondBytes = parseHex(hex); return this; }
        public Builder stepOrder(int v) { this.stepOrder = v; return this; }

        public Expectation build() {
            return new Expectation(
                    id != null ? id : "exp-" + UUID.randomUUID(),
                    port, sessionId, onReceiveBytes, respondBytes, stepOrder);
        }

        private static byte[] parseHex(String hex) {
            String clean = hex.replace(" ", "").replace(":", "");
            if (clean.length() % 2 != 0) {
                throw new IllegalArgumentException("hex length not even: " + hex);
            }
            byte[] out = new byte[clean.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
    }
}
