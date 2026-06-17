package io.graphrag.builder.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 결정적 W3C traceparent 생성기. runId 시드 + 요청별 단조 카운터를 SHA-256으로 섞어
 * 16-byte traceId / 8-byte spanId를 만든다. Math.random / new Date 미사용 (재현성).
 */
public final class TraceParent {

    private final byte[] seed;
    private final AtomicLong counter = new AtomicLong();

    public TraceParent(String runId) {
        this.seed = runId.getBytes(StandardCharsets.UTF_8);
    }

    public Ids next() {
        long n = counter.getAndIncrement();
        byte[] digest = sha256(seed, n);
        String traceId = hex(digest, 0, 16);
        String spanId = hex(digest, 16, 8);
        return new Ids(traceId, spanId);
    }

    public record Ids(String traceId, String spanId) {
        public String header() {
            return "00-" + traceId + "-" + spanId + "-01";
        }
    }

    private static byte[] sha256(byte[] seed, long n) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(seed);
            for (int i = 0; i < 8; i++) {
                md.update((byte) (n >>> (i * 8)));
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return sb.toString();
    }
}
