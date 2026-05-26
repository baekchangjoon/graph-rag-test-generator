package io.graphrag.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingStreamsTest {

    @Test
    void recordingInputStreamCapturesReadBytes() throws Exception {
        ByteArrayInputStream src = new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RecordingInputStream rec = new RecordingInputStream(src,
                (b, off, len) -> sink.write(b, off, len));

        byte[] buf = new byte[3];
        int n1 = rec.read(buf, 0, 3);
        int n2 = rec.read();
        assertThat(n1).isEqualTo(3);
        assertThat(n2).isEqualTo(4);
        assertThat(sink.toByteArray()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void recordingOutputStreamCapturesWrittenBytes() throws Exception {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        RecordingOutputStream rec = new RecordingOutputStream(actual,
                (b, off, len) -> record.write(b, off, len));

        rec.write(new byte[] {10, 20, 30}, 0, 3);
        rec.write(40);

        assertThat(actual.toByteArray()).containsExactly(10, 20, 30, 40);
        assertThat(record.toByteArray()).containsExactly(10, 20, 30, 40);
    }

    @Test
    void recordingInputStreamFallsBackToInternalBufferWhenNoSink() throws Exception {
        ByteArrayInputStream src = new ByteArrayInputStream(new byte[] {5, 6, 7});
        RecordingInputStream rec = new RecordingInputStream(src, null);

        byte[] buf = new byte[3];
        rec.read(buf, 0, 3);

        assertThat(rec.collectedBytes()).containsExactly(5, 6, 7);
    }

    @Test
    void socketByteRecorderWrapsRealSocketAndCapturesIO() throws Exception {
        // 실 java.net.Socket을 ServerSocket과 함께 만들어 데이터 송수신
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket client = server.accept();
                     InputStream in = client.getInputStream();
                     OutputStream out = client.getOutputStream()) {
                    byte[] buf = new byte[3];
                    int n = in.read(buf, 0, 3);
                    assertThat(n).isEqualTo(3);
                    out.write(new byte[] {(byte) 0xFF, (byte) 0xEE});
                    out.flush();
                } catch (Exception ignored) {}
            });
            serverThread.start();

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                SocketByteRecorder.Wrapped wrapped = SocketByteRecorder.wrap(socket);

                wrapped.output.write(new byte[] {1, 2, 3});
                wrapped.output.flush();

                byte[] resp = new byte[2];
                int n = wrapped.input.read(resp, 0, 2);
                assertThat(n).isEqualTo(2);
                assertThat(resp).containsExactly((byte) 0xFF, (byte) 0xEE);

                assertThat(wrapped.writtenBuffer.toByteArray()).containsExactly(1, 2, 3);
                assertThat(wrapped.readBuffer.toByteArray())
                        .containsExactly((byte) 0xFF, (byte) 0xEE);
            }
            serverThread.join(2000);
        }
    }

    @Test
    void toHexFormatsBytes() {
        assertThat(SocketByteRecorder.toHex(new byte[] {1, 2, (byte) 0xFF}))
                .isEqualTo("01 02 FF");
    }
}
