package io.graphrag.agent;

import java.io.ByteArrayOutputStream;
import java.net.Socket;

/**
 * {@link java.net.Socket}의 input/output stream을 recording stream으로 감싸는 헬퍼.
 *
 * <p>{@link Socket}을 인자로 받아 {@link RecordingInputStream}/{@link RecordingOutputStream}을
 * 반환. 호출자가 명시적으로 wrap하는 형태 — agent가 자동으로 system class를 instrument하는 것은
 * boot classpath 설정이 필요해 별도 작업 (Phase 5+).
 */
public final class SocketByteRecorder {

    private SocketByteRecorder() {}

    /** Recorded streams를 보유하는 결과 객체. */
    public static final class Wrapped {
        public final RecordingInputStream input;
        public final RecordingOutputStream output;
        public final ByteArrayOutputStream readBuffer;
        public final ByteArrayOutputStream writtenBuffer;

        Wrapped(RecordingInputStream in, RecordingOutputStream out,
                ByteArrayOutputStream readBuf, ByteArrayOutputStream writeBuf) {
            this.input = in;
            this.output = out;
            this.readBuffer = readBuf;
            this.writtenBuffer = writeBuf;
        }
    }

    public static Wrapped wrap(Socket socket) {
        try {
            ByteArrayOutputStream readBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream writeBuf = new ByteArrayOutputStream();
            RecordingInputStream in = new RecordingInputStream(
                    socket.getInputStream(),
                    (b, off, len) -> readBuf.write(b, off, len));
            RecordingOutputStream out = new RecordingOutputStream(
                    socket.getOutputStream(),
                    (b, off, len) -> writeBuf.write(b, off, len));
            return new Wrapped(in, out, readBuf, writeBuf);
        } catch (Exception ex) {
            throw new RuntimeException("failed to wrap socket streams", ex);
        }
    }

    /** byte[]를 hex 문자열 ("01 02 03")로 포맷. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
