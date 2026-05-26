package io.graphrag.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 다른 {@link InputStream}을 감싸 읽힌 바이트를 기록.
 *
 * <p>captureCallback이 매 read 호출 결과 byte 청크를 받는다.
 * Phase 5 minimum: byte 자체 + 길이만 기록. 큰 read 분할은 호출자 책임.
 */
public final class RecordingInputStream extends InputStream {

    public interface ByteSink {
        void accept(byte[] bytes, int offset, int length);
    }

    private final InputStream delegate;
    private final ByteSink sink;
    private final List<byte[]> buffered = new ArrayList<>();

    public RecordingInputStream(InputStream delegate, ByteSink sink) {
        this.delegate = delegate;
        this.sink = sink == null ? this::bufferOnly : sink;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b >= 0) {
            byte[] one = new byte[] {(byte) b};
            sink.accept(one, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = delegate.read(b, off, len);
        if (n > 0) {
            sink.accept(b, off, n);
        }
        return n;
    }

    @Override
    public int available() throws IOException { return delegate.available(); }

    @Override
    public void close() throws IOException { delegate.close(); }

    /** sink가 null일 때 폴백: 내부 버퍼에 누적. */
    private void bufferOnly(byte[] b, int off, int len) {
        byte[] copy = new byte[len];
        System.arraycopy(b, off, copy, 0, len);
        synchronized (buffered) { buffered.add(copy); }
    }

    /** 폴백 버퍼 접근. */
    public byte[] collectedBytes() {
        synchronized (buffered) {
            int total = buffered.stream().mapToInt(arr -> arr.length).sum();
            byte[] out = new byte[total];
            int pos = 0;
            for (byte[] arr : buffered) {
                System.arraycopy(arr, 0, out, pos, arr.length);
                pos += arr.length;
            }
            return out;
        }
    }
}
