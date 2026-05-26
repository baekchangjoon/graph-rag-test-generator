package io.graphrag.demo.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Phase 5 외부 가격 시스템 클라이언트 — raw {@link java.net.Socket}.
 *
 * <p>{@link NettyPricingClient}와 동일 프로토콜이나 NIO/Netty 없이 blocking I/O 사용.
 * 분석 시 {@code io.graphrag.agent.SocketByteRecorder}로 stream을 wrap하면 byte 시퀀스 캡처 가능.
 */
@Component
public class RawSocketPricingClient {

    private final String host;
    private final int port;

    public RawSocketPricingClient(
            @Value("${external.pricing.host:127.0.0.1}") String host,
            @Value("${external.pricing.port:9000}") int port) {
        this.host = host;
        this.port = port;
    }

    /** raw socket으로 4 byte 요청 → 4 byte 응답 (BE int). 실패 시 -1. */
    public int fetchPriceRaw(byte[] itemCode) {
        if (itemCode == null || itemCode.length != 4) {
            throw new IllegalArgumentException("itemCode must be 4 bytes");
        }
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress(host, port), 3000);
            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {
                out.write(itemCode);
                out.flush();
                byte[] resp = new byte[4];
                int read = 0;
                while (read < 4) {
                    int n = in.read(resp, read, 4 - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read < 4) return -1;
                return ((resp[0] & 0xFF) << 24)
                        | ((resp[1] & 0xFF) << 16)
                        | ((resp[2] & 0xFF) << 8)
                        | (resp[3] & 0xFF);
            }
        } catch (Exception ex) {
            return -1;
        }
    }
}
