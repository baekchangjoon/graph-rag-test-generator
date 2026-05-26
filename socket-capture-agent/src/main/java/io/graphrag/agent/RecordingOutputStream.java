package io.graphrag.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 다른 {@link OutputStream}을 감싸 쓰여진 바이트를 기록.
 */
public final class RecordingOutputStream extends OutputStream {

    public interface ByteSink {
        void accept(byte[] bytes, int offset, int length);
    }

    private final OutputStream delegate;
    private final ByteSink sink;
    private final List<byte[]> buffered = new ArrayList<>();

    public RecordingOutputStream(OutputStream delegate, ByteSink sink) {
        this.delegate = delegate;
        this.sink = sink == null ? this::bufferOnly : sink;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        byte[] one = new byte[] {(byte) b};
        sink.accept(one, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        sink.accept(b, off, len);
    }

    @Override
    public void flush() throws IOException { delegate.flush(); }

    @Override
    public void close() throws IOException { delegate.close(); }

    private void bufferOnly(byte[] b, int off, int len) {
        byte[] copy = new byte[len];
        System.arraycopy(b, off, copy, 0, len);
        synchronized (buffered) { buffered.add(copy); }
    }

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
